package org.lflang.analyses.evm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.stream.IntStream;
import org.lflang.FileConfig;
import org.lflang.TimeValue;
import org.lflang.analyses.dag.Dag;
import org.lflang.analyses.dag.DagEdge;
import org.lflang.analyses.dag.DagNode;
import org.lflang.analyses.dag.DagNode.dagNodeType;
import org.lflang.analyses.evm.Instruction.Opcode;
import org.lflang.analyses.statespace.StateSpaceDiagram;
import org.lflang.analyses.statespace.StateSpaceFragment;
import org.lflang.generator.CodeBuilder;
import org.lflang.generator.ReactionInstance;
import org.lflang.generator.ReactorInstance;
import org.lflang.generator.TimerInstance;

public class InstructionGenerator {

  /** File configuration */
  FileConfig fileConfig;

  /** A list of reactor instances in the program */
  List<ReactorInstance> reactors;

  /** A list of reaction instances in the program */
  List<ReactionInstance> reactions;

  /** Number of workers */
  int workers;

  /** Constructor */
  public InstructionGenerator(
      FileConfig fileConfig,
      int workers,
      List<ReactorInstance> reactors,
      List<ReactionInstance> reactions) {
    this.fileConfig = fileConfig;
    this.workers = workers;
    this.reactors = reactors;
    this.reactions = reactions;
  }

  /** Traverse the DAG from head to tail using Khan's algorithm (topological sort). */
  public EvmObjectFile generateInstructions(Dag dagParitioned, StateSpaceFragment fragment) {

    /** Instructions for all workers */
    List<List<Instruction>> instructions = new ArrayList<>();
    for (int i = 0; i < workers; i++) {
      instructions.add(new ArrayList<Instruction>());
    }

    // Initialize a queue and a map to hold the indegree of each node.
    Queue<DagNode> queue = new LinkedList<>();
    Map<DagNode, Integer> indegree = new HashMap<>();

    // Initialize a reaction index array to keep track of the latest counting
    // lock value for each worker.
    int[] countLockValues = new int[workers];

    // Debug
    int count = 0;

    // Add BIT instructions regardless of timeout
    // is specified in the program because it could be
    // specified on the command line.
    for (var schedule : instructions) {
      schedule.add(new InstructionBIT());
    }

    // Initialize indegree of all nodes to be the size of their respective upstream node set.
    for (DagNode node : dagParitioned.dagNodes) {
      indegree.put(node, dagParitioned.dagEdgesRev.getOrDefault(node, new HashMap<>()).size());
      // Add the node with zero indegree to the queue.
      if (dagParitioned.dagEdgesRev.getOrDefault(node, new HashMap<>()).size() == 0) {
        queue.add(node);
      }
    }

    // The main loop for traversal using an iterative topological sort.
    while (!queue.isEmpty()) {
      // Dequeue a node.
      DagNode current = queue.poll();

      // Debug
      current.setDotDebugMsg("count: " + count++);
      // System.out.println("Current: " + current);

      // Get the upstream reaction nodes.
      List<DagNode> upstreamReactionNodes =
          dagParitioned.dagEdgesRev.getOrDefault(current, new HashMap<>()).keySet().stream()
              .filter(n -> n.nodeType == dagNodeType.REACTION)
              .toList();
      // System.out.println("Upstream reaction nodes: " + upstreamReactionNodes);

      // Get the upstream sync nodes.
      List<DagNode> upstreamSyncNodes =
          dagParitioned.dagEdgesRev.getOrDefault(current, new HashMap<>()).keySet().stream()
              .filter(n -> n.nodeType == dagNodeType.SYNC)
              .toList();
      // System.out.println("Upstream sync nodes: " + upstreamSyncNodes);

      /* Generate instructions for the current node */
      if (current.nodeType == dagNodeType.REACTION) {
        // If the reaction depends on upstream reactions owned by other
        // workers, generate WU instructions to resolve the dependencies.
        // FIXME: Check if upstream reactions contain reactions owned by
        // other workers. If so, insert a WU with other workers'
        // countLockValues. The current implementation generates multiple WUs.
        for (DagNode n : upstreamReactionNodes) {
          int upstreamOwner = n.getWorker();
          if (upstreamOwner != current.getWorker()) {
            instructions
                .get(current.getWorker())
                .add(new InstructionWU(upstreamOwner, countLockValues[upstreamOwner]));
          }
        }

        // If the reaction depends on a SYNC node,
        // advance to the logical time of the SYNC node first.
        // Skip if it is the head node.
        if (upstreamSyncNodes.size() == 1 && upstreamSyncNodes.get(0) != dagParitioned.head) {
          instructions
              .get(current.getWorker())
              .add(
                  new InstructionADV2(
                      current.getReaction().getParent(), upstreamSyncNodes.get(0).timeStep));
        } else if (upstreamSyncNodes.size() > 1)
          System.out.println("WARNING: More than one upstream SYNC nodes detected.");

        // If the reaction is triggered by a timer,
        // generate an EXE instruction.
        // FIXME: Handle a reaction triggered by both timers and ports.
        ReactionInstance reaction = current.getReaction();
        if (reaction.triggers.stream().anyMatch(trigger -> trigger instanceof TimerInstance)) {
          instructions.get(current.getWorker()).add(new InstructionEXE(reaction));
        }
        // Otherwise, generate an EIT instruction.
        else {
          instructions.get(current.getWorker()).add(new InstructionEIT(reaction));
        }

        // Increment the counter of the worker.
        instructions.get(current.getWorker()).add(new InstructionINC2());
        countLockValues[current.getWorker()]++;

      } else if (current.nodeType == dagNodeType.SYNC) {
        if (current != dagParitioned.head && current != dagParitioned.tail) {
          // If a worker has reactions that lead to this SYNC node,
          // insert a DU in the schedule.
          // FIXME: Here we have an implicit assumption "logical time is
          // physical time." We need to find a way to relax this assumption.
          for (var i = 0; i < workers; i++) {
            final int j = i; // Need a final int to use the stream method.
            if (upstreamReactionNodes.stream().anyMatch(n -> n.getWorker() == j)) {
              instructions.get(j).add(new InstructionDU(current.timeStep));
            }
          }
        } else if (current == dagParitioned.tail) {
          for (var schedule : instructions) {
            // Add an SAC instruction.
            schedule.add(new InstructionSAC(current.timeStep));
            // Add a DU instruction.
            schedule.add(new InstructionDU(current.timeStep));
          }
        }
      }

      // Visit each downstream node.
      HashMap<DagNode, DagEdge> innerMap = dagParitioned.dagEdges.get(current);
      if (innerMap != null) {
        for (DagNode n : innerMap.keySet()) {
          // Decrease the indegree of the downstream node.
          int updatedIndegree = indegree.get(n) - 1;
          indegree.put(n, updatedIndegree);

          // If the downstream node has zero indegree now, add it to the queue.
          if (updatedIndegree == 0) {
            queue.add(n);
          }
        }
      }
    }

    // Check if all nodes are visited (i.e., indegree of all nodes are 0).
    if (indegree.values().stream().anyMatch(deg -> deg != 0)) {
      // The graph has at least one cycle.
      throw new RuntimeException(
          "The graph has at least one cycle, thus cannot be topologically sorted.");
    }

    // Add JMP and STP instructions for jumping back to the beginning.
    if (fragment.isCyclic()) {
      for (var schedule : instructions) {
        schedule.add(new InstructionJMP(schedule.get(0))); // Jump to the first instruction.
        schedule.add(new InstructionSTP());
      }
    }

    return new EvmObjectFile(instructions, fragment);
  }

  /** Generate C code from the instructions list. */
  public void generateCode(EvmExecutable executable) {
    List<List<Instruction>> instructions = executable.getContent();

    // Instantiate a code builder.
    Path srcgen = fileConfig.getSrcGenPath();
    Path file = srcgen.resolve("schedule.c");
    CodeBuilder code = new CodeBuilder();

    // Generate a block comment.
    code.pr(
        String.join(
            "\n",
            "/**",
            " * An auto-generated schedule file for the FS scheduler.",
            " * ",
            " * reactor array:",
            " * " + this.reactors,
            " * ",
            " * reaction array:",
            " * " + this.reactions,
            " */"));

    // Header files
    code.pr(
        String.join(
            "\n",
            "#include <stdint.h>",
            "#include <stddef.h> // size_t",
            "#include \"core/threaded/scheduler_instructions.h\""));

    for (int i = 0; i < instructions.size(); i++) {
      var schedule = instructions.get(i);
      code.pr("const inst_t schedule_" + i + "[] = {");
      code.indent();

      for (int j = 0; j < schedule.size(); j++) {
        Instruction inst = schedule.get(j);
        // System.out.println("Opcode is " + inst.getOpcode());
        switch (inst.getOpcode()) {
          case ADV2:
            {
              ReactorInstance reactor = ((InstructionADV2) inst).reactor;
              TimeValue nextTime = ((InstructionADV2) inst).nextTime;
              code.pr(
                  "{.op="
                      + inst.getOpcode()
                      + ", "
                      + ".rs1="
                      + reactors.indexOf(reactor)
                      + ", "
                      + ".rs2="
                      + nextTime.toNanoSeconds()
                      + "LL"
                      + "}"
                      + ","
                      + " // (Lock-free) advance the logical time of "
                      + reactor
                      + " to "
                      + nextTime
                      + " wrt the hyperperiod");
              break;
            }
          case BIT:
            {
              int stopIndex =
                  IntStream.range(0, schedule.size())
                      .filter(k -> (schedule.get(k).getOpcode() == Opcode.STP))
                      .findFirst()
                      .getAsInt();
              code.pr(
                  "{.op="
                      + inst.getOpcode()
                      + ", "
                      + ".rs1="
                      + stopIndex
                      + ", "
                      + ".rs2="
                      + "-1"
                      + "}"
                      + ","
                      + " // Branch, if timeout, to line "
                      + stopIndex);
              break;
            }
          case DU:
            {
              TimeValue releaseTime = ((InstructionDU) inst).releaseTime;
              code.pr(
                  "{.op="
                      + inst.getOpcode()
                      + ", "
                      + ".rs1="
                      + releaseTime.toNanoSeconds()
                      + "LL"
                      + ", "
                      + ".rs2="
                      + -1
                      + "}"
                      + ","
                      + " // Delay Until "
                      + releaseTime
                      + "  wrt the current hyperperiod is reached.");
              break;
            }
          case EIT:
            {
              ReactionInstance reaction = ((InstructionEIT) inst).reaction;
              code.pr(
                  "{.op="
                      + inst.getOpcode()
                      + ", "
                      + ".rs1="
                      + reactions.indexOf(reaction)
                      + ", "
                      + ".rs2="
                      + -1
                      + "}"
                      + ","
                      + " // Execute reaction "
                      + reaction
                      + " if it is marked as queued by the runtime");
              break;
            }
          case EXE:
            {
              ReactionInstance reaction = ((InstructionEXE) inst).reaction;
              code.pr(
                  "{.op="
                      + inst.getOpcode()
                      + ", "
                      + ".rs1="
                      + reactions.indexOf(reaction)
                      + ", "
                      + ".rs2="
                      + -1
                      + "}"
                      + ","
                      + " // Execute reaction "
                      + reaction);
              break;
            }
          case INC2:
            {
              code.pr(
                  "{.op="
                      + inst.getOpcode()
                      + ", "
                      + ".rs1="
                      + i
                      + ", "
                      + ".rs2="
                      + 1
                      + "}"
                      + ","
                      + " // (Lock-free) increment counter "
                      + i
                      + " by 1");
              break;
            }
          case JMP:
            Instruction target = ((InstructionJMP) inst).target;
            int lineNo = schedule.indexOf(target);
            code.pr(
                "{.op="
                    + inst.getOpcode()
                    + ", "
                    + ".rs1="
                    + lineNo
                    + ", "
                    + ".rs2="
                    + 0
                    + "}"
                    + ","
                    + " // Jump to line " + lineNo + " and increment the iteration counter by 1");
            break;
          case SAC:
            TimeValue nextTime = ((InstructionSAC) inst).nextTime;
            code.pr(
                "{.op="
                    + inst.getOpcode()
                    + ", "
                    + ".rs1="
                    + nextTime.toNanoSeconds()
                    + "LL"
                    + ", "
                    + ".rs2="
                    + -1
                    + "}"
                    + ","
                    + " // Sync all workers at this instruction and clear all counters");
            break;
          case STP:
            code.pr(
                "{.op="
                    + inst.getOpcode()
                    + ", "
                    + ".rs1="
                    + -1
                    + ", "
                    + ".rs2="
                    + -1
                    + "}"
                    + ","
                    + " // Stop the execution");
            break;
          case WU:
            int worker = ((InstructionWU) inst).worker;
            int releaseValue = ((InstructionWU) inst).releaseValue;
            code.pr(
                "{.op="
                    + inst.getOpcode()
                    + ", "
                    + ".rs1="
                    + worker
                    + ", "
                    + ".rs2="
                    + releaseValue
                    + "}"
                    + ","
                    + " // Wait until counter "
                    + worker
                    + " reaches "
                    + releaseValue);
            break;
          default:
            throw new RuntimeException("UNREACHABLE!");
        }
      }

      code.unindent();
      code.pr("};");
    }

    // Generate an array to store the schedule pointers.
    code.pr("const inst_t* static_schedules[] = {");
    code.indent();
    for (int i = 0; i < instructions.size(); i++) {
      code.pr("schedule_" + i + ",");
    }
    code.unindent();
    code.pr("};");

    // Generate counters.
    code.pr("volatile uint32_t counters[" + workers + "] = {0};");
    code.pr("const size_t num_counters = " + workers + ";");
    code.pr("volatile uint32_t hyperperiod_iterations[" + workers + "] = {0};");
    code.pr("const long long int hyperperiod = " + executable.getHyperperiod() + ";");

    // Print to file.
    try {
      code.writeToFile(file.toString());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Pretty printing instructions */
  public void display(EvmObjectFile objectFile) {
    List<List<Instruction>> instructions = objectFile.getContent();
    for (int i = 0; i < instructions.size(); i++) {
      List<Instruction> schedule = instructions.get(i);
      System.out.println("Worker " + i + ":");
      for (int j = 0; j < schedule.size(); j++) {
        System.out.println(schedule.get(j));
      }
    }
  }

  /** Link multiple object files into a single executable (represented also in
   * an object file class). In the future, when physical actions are supported,
   * this method will add conditional jumps based on predicates. */
  public EvmExecutable link(List<EvmObjectFile> evmObjectFiles) {

    // Create empty schedules.
    List<List<Instruction>> schedules = new ArrayList<>();
    for (int i = 0; i < workers; i++) {
      schedules.add(new ArrayList<Instruction>());
    }

    // Populate the schedules.
    for (int j = 0; j < evmObjectFiles.size(); j++) {
      EvmObjectFile obj = evmObjectFiles.get(j);

      // The upstream/downstream info is used trivially here,
      // when evmObjectFiles has at most two elements (init, periodic).
      // In the future, this part will be used more meaningfully. 
      if (j == 0) assert obj.getFragment().getUpstream() == null;
      else if (j == evmObjectFiles.size() - 1)
        assert obj.getFragment().getDownstream() == null;

      // Simply stitch all parts together.
      List<List<Instruction>> partialSchedules = obj.getContent();
      for (int i = 0; i < workers; i++) {
        schedules.get(i).addAll(partialSchedules.get(i));
      }
    }

    return new EvmExecutable(schedules, evmObjectFiles.get(evmObjectFiles.size()-1).getHyperperiod());
  }
}
