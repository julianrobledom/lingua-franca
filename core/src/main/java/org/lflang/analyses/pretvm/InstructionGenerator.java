package org.lflang.analyses.pretvm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.lflang.FileConfig;
import org.lflang.TimeValue;
import org.lflang.analyses.dag.Dag;
import org.lflang.analyses.dag.DagEdge;
import org.lflang.analyses.dag.DagNode;
import org.lflang.analyses.dag.DagNode.dagNodeType;
import org.lflang.analyses.statespace.StateSpaceExplorer.Phase;
import org.lflang.analyses.statespace.StateSpaceFragment;
import org.lflang.analyses.statespace.StateSpaceUtils;
import org.lflang.generator.ActionInstance;
import org.lflang.generator.CodeBuilder;
import org.lflang.generator.PortInstance;
import org.lflang.generator.ReactionInstance;
import org.lflang.generator.ReactorInstance;
import org.lflang.generator.TriggerInstance;
import org.lflang.generator.c.CUtil;
import org.lflang.generator.c.TypeParameterizedReactor;
import org.lflang.target.TargetConfig;
import org.lflang.target.property.FastProperty;
import org.lflang.target.property.TimeOutProperty;

/**
 * A generator that generates PRET VM programs from DAGs. It also acts as a linker that piece
 * together multiple PRET VM object files.
 *
 * @author Shaokai Lin
 */
public class InstructionGenerator {

  /** File configuration */
  FileConfig fileConfig;

  /** Target configuration */
  TargetConfig targetConfig;

  /** Main reactor instance */
  protected ReactorInstance main;

  /** A list of reactor instances in the program */
  List<ReactorInstance> reactors;

  /** A list of reaction instances in the program */
  List<ReactionInstance> reactions;

  /** A list of trigger instances in the program */
  List<TriggerInstance> triggers;

  /** Number of workers */
  int workers;

  /** 
   * A mapping remembering where to fill in the placeholders
   * Each element of the list corresponds to a worker. The PretVmLabel marks the
   * line to be updated. The String is the variable to be written into the
   * schedule.
   */
  private List<Map<PretVmLabel, String>> placeholderMaps = new ArrayList<>(); 

  /** Constructor */
  public InstructionGenerator(
      FileConfig fileConfig,
      TargetConfig targetConfig,
      int workers,
      ReactorInstance main,
      List<ReactorInstance> reactors,
      List<ReactionInstance> reactions,
      List<TriggerInstance> triggers) {
    this.fileConfig = fileConfig;
    this.targetConfig = targetConfig;
    this.workers = workers;
    this.main = main;
    this.reactors = reactors;
    this.reactions = reactions;
    this.triggers = triggers;
    for (int i = 0; i < this.workers; i++)
        placeholderMaps.add(new HashMap<>());
  }

  /** Topologically sort the dag nodes and assign release values to DAG nodes for counting locks. */
  public void assignReleaseValues(Dag dagParitioned) {
    // Initialize a reaction index array to keep track of the latest counting
    // lock value for each worker.
    Long[] releaseValues = new Long[workers];
    Arrays.fill(releaseValues, 0L); // Initialize all elements to 0

    // Iterate over a topologically sorted list of dag nodes.
    for (DagNode current : dagParitioned.getTopologicalSort()) {
      if (current.nodeType == dagNodeType.REACTION) {
        releaseValues[current.getWorker()] += 1;
        current.setReleaseValue(releaseValues[current.getWorker()]);
      }
    }
  }

  /** Traverse the DAG from head to tail using Khan's algorithm (topological sort). */
  public PretVmObjectFile generateInstructions(Dag dagParitioned, StateSpaceFragment fragment) {
    // Map from a reactor to its latest associated SYNC node.
    // This is used to determine when ADVIs and DUs should be generated without
    // duplicating them for each reaction node in the same reactor.
    Map<ReactorInstance, DagNode> reactorToLastSyncNodeMap = new HashMap<>();

    // Assign release values for the reaction nodes.
    assignReleaseValues(dagParitioned);

    // Instructions for all workers
    List<List<Instruction>> instructions = new ArrayList<>();
    for (int i = 0; i < workers; i++) {
      instructions.add(new ArrayList<Instruction>());
    }

    // Iterate over a topologically sorted list of dag nodes.
    for (DagNode current : dagParitioned.getTopologicalSort()) {
      // Get the upstream reaction nodes.
      List<DagNode> upstreamReactionNodes =
          dagParitioned.dagEdgesRev.getOrDefault(current, new HashMap<>()).keySet().stream()
              .filter(n -> n.nodeType == dagNodeType.REACTION)
              .toList();

      if (current.nodeType == dagNodeType.REACTION) {

        // Get the nearest upstream sync node.
        DagNode associatedSyncNode = current.getAssociatedSyncNode();

        // If the reaction depends on upstream reactions owned by other
        // workers, generate WU instructions to resolve the dependencies.
        // FIXME: The current implementation generates multiple unnecessary WUs
        // for simplicity. How to only generate WU when necessary?
        for (DagNode n : upstreamReactionNodes) {
          int upstreamOwner = n.getWorker();
          if (upstreamOwner != current.getWorker()) {
            instructions
                .get(current.getWorker())
                .add(
                    new InstructionWU(
                        GlobalVarType.WORKER_COUNTER, upstreamOwner, n.getReleaseValue()));
          }
        }

        // When the new associated sync node _differs_ from the last associated sync
        // node of the reactor, this means that the current node's reactor needs
        // to advance to a new tag. The code should update the associated sync
        // node in the map. And if associatedSyncNode is not the head, generate
        // the ADVI and DU instructions. 
        //
        // TODO: The next step is to generate EXE instructions for putting
        // tokens into the pqueue before executing the ADVI instruction for the
        // reactor about to advance time.
        ReactorInstance currentReactor = current.getReaction().getParent();
        if (associatedSyncNode != reactorToLastSyncNodeMap.get(currentReactor)) {
          // Update the mapping.
          reactorToLastSyncNodeMap.put(currentReactor, associatedSyncNode);

          // If the reaction depends on a single SYNC node,
          // advance to the LOGICAL time of the SYNC node first,
          // as well as delay until the PHYSICAL time indicated by the SYNC node.
          // Skip if it is the head node since this is done in SAC.
          // FIXME: Here we have an implicit assumption "logical time is
          // physical time." We need to find a way to relax this assumption.
          if (associatedSyncNode != dagParitioned.head) {
            // Generate an ADVI instruction.
            var reactor = current.getReaction().getParent();
            var advi = new InstructionADVI(
                        current.getReaction().getParent(),
                        GlobalVarType.GLOBAL_OFFSET,
                        associatedSyncNode.timeStep.toNanoSeconds());
            var uuid = generateShortUUID();
            advi.setLabel("ADVANCE_TAG_FOR_" + reactor.getFullNameWithJoiner("_") + "_" + uuid);
            placeholderMaps.get(current.getWorker()).put(
              advi.getLabel(),
              getReactorFromEnv(main, reactor));
            instructions
                .get(current.getWorker())
                .add(advi);
            // Generate a DU instruction if fast mode is off.
            if (!targetConfig.get(FastProperty.INSTANCE)) {
              instructions
                  .get(current.getWorker())
                  .add(new InstructionDU(associatedSyncNode.timeStep));
            }
          }
        }

        // If the reaction is triggered by startup, shutdown, or a timer,
        // generate an EXE instruction.
        // FIXME: Handle a reaction triggered by both timers and ports.
        ReactionInstance reaction = current.getReaction();
        // Create an EXE instruction.
        Instruction exe = new InstructionEXE(reaction);
        exe.setLabel("EXECUTE_" + reaction.getFullNameWithJoiner("_") + "_" + generateShortUUID());
        placeholderMaps.get(current.getWorker()).put(
            exe.getLabel(),
            getReactionFromEnv(main, reaction));
        // Check if the reaction has BEQ guards or not.
        boolean hasGuards = false;
        // Create BEQ instructions for checking triggers.
        for (var trigger : reaction.triggers) {
          if (hasIsPresentField(trigger)) {
            hasGuards = true;
            var beq = new InstructionBEQ(getTriggerPresenceFromEnv(main, trigger), GlobalVarType.GLOBAL_ONE, exe.getLabel());
            beq.setLabel("TEST_TRIGGER_" + trigger.getFullNameWithJoiner("_") + "_" + generateShortUUID());
            placeholderMaps.get(current.getWorker()).put(
              beq.getLabel(),
              getTriggerPresenceFromEnv(main, trigger));
            instructions.get(current.getWorker()).add(beq);
          }
        }

        // Instantiate an ADDI to be executed after EXE.
        var addi = new InstructionADDI(
                    GlobalVarType.WORKER_COUNTER,
                    current.getWorker(),
                    GlobalVarType.WORKER_COUNTER,
                    current.getWorker(),
                    1L);
        // And create a label for it as a JAL target in case EXE is not
        // executed.
        addi.setLabel("ONE_LINE_AFTER_EXE_" + generateShortUUID());

        // If none of the guards are activated, jump to one line after the
        // EXE instruction. 
        if (hasGuards) instructions.get(current.getWorker()).add(new InstructionJAL(GlobalVarType.GLOBAL_ZERO, addi.getLabel()));
        
        // Add EXE to the schedule.
        instructions.get(current.getWorker()).add(exe);

        // Increment the counter of the worker.
        instructions
            .get(current.getWorker())
            .add(addi);
      } else if (current.nodeType == dagNodeType.SYNC) {
        if (current == dagParitioned.tail) {
          // When the timeStep = TimeValue.MAX_VALUE in a SYNC node,
          // this means that the DAG is acyclic and can end without
          // real-time constraints, hence we do not genereate SAC,
          // DU, and ADDI.
          if (current.timeStep != TimeValue.MAX_VALUE) {
            for (int worker = 0; worker < workers; worker++) {
              List<Instruction> schedule = instructions.get(worker);
              // Add a DU instruction if fast mode is off.
              if (!targetConfig.get(FastProperty.INSTANCE))
                schedule.add(new InstructionDU(current.timeStep));
              // [Only Worker 0] Update the time increment register.
              if (worker == 0) {
                schedule.add(
                    new InstructionADDI(
                        GlobalVarType.GLOBAL_OFFSET_INC,
                        null,
                        GlobalVarType.GLOBAL_ZERO,
                        null,
                        current.timeStep.toNanoSeconds()));
              }
              // Let all workers go to SYNC_BLOCK after finishing PREAMBLE.
              schedule.add(new InstructionJAL(GlobalVarType.WORKER_RETURN_ADDR, Phase.SYNC_BLOCK));
            }
          }
        }
      }
    }
    return new PretVmObjectFile(instructions, fragment);
  }

  // FIXME: Instead of finding this manually, we can store this information when
  // building the DAG.
  private DagNode findNearestUpstreamSync(
      DagNode node, Map<DagNode, HashMap<DagNode, DagEdge>> dagEdgesRev) {
    if (node.nodeType == dagNodeType.SYNC) {
      return node;
    }

    HashMap<DagNode, DagEdge> upstreamNodes = dagEdgesRev.getOrDefault(node, new HashMap<>());
    for (DagNode upstreamNode : upstreamNodes.keySet()) {
      DagNode result = findNearestUpstreamSync(upstreamNode, dagEdgesRev);
      if (result != null) {
        return result;
      }
    }

    return null;
  }

  /** Generate C code from the instructions list. */
  public void generateCode(PretVmExecutable executable) {
    List<List<Instruction>> instructions = executable.getContent();

    // Instantiate a code builder.
    Path srcgen = fileConfig.getSrcGenPath();
    Path file = srcgen.resolve("static_schedule.c");
    CodeBuilder code = new CodeBuilder();

    // Generate a block comment.
    code.pr(
        String.join(
            "\n",
            "/**",
            " * An auto-generated schedule file for the STATIC scheduler.",
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
            "#include \"core/environment.h\"",
            "#include \"core/threaded/scheduler_instance.h\"",
            // "#include \"core/threaded/scheduler_instructions.h\"",
            "#include " + "\"" + fileConfig.name + ".h" + "\""));

    // Include reactor header files.
    List<TypeParameterizedReactor> tprs = this.reactors.stream().map(it -> it.tpr).toList();
    Set<String> headerNames = CUtil.getNames(tprs);
    for (var name : headerNames) {
      code.pr("#include " + "\"" + name + ".h" + "\"");
    }

    // Generate label macros.
    // Future FIXME: Make sure that label strings are formatted properly and are
    // unique, when the user is allowed to define custom labels. Currently,
    // all Phase enums are formatted properly.
    for (int i = 0; i < instructions.size(); i++) {
      var schedule = instructions.get(i);
      for (int j = 0; j < schedule.size(); j++) {
        if (schedule.get(j).hasLabel()) {
          code.pr("#define " + getWorkerLabelString(schedule.get(j).getLabel(), i) + " " + j);
        }
      }
    }
    code.pr("#define " + getPlaceHolderMacro() + " " + "NULL");

    // Extern variables
    code.pr("// Extern variables");
    code.pr("extern environment_t envs[_num_enclaves];");
    code.pr("extern instant_t " + getVarName(GlobalVarType.EXTERN_START_TIME, null, false) + ";");

    // Runtime variables
    code.pr("// Runtime variables");
    if (targetConfig.isSet(TimeOutProperty.INSTANCE))
      // FIXME: Why is timeout volatile?
      code.pr(
          "volatile uint64_t "
              + getVarName(GlobalVarType.GLOBAL_TIMEOUT, null, false)
              + " = "
              + targetConfig.get(TimeOutProperty.INSTANCE).toNanoSeconds()
              + "LL"
              + ";");
    code.pr("const size_t num_counters = " + workers + ";"); // FIXME: Seems unnecessary.
    code.pr("volatile reg_t " + getVarName(GlobalVarType.GLOBAL_OFFSET, workers, false) + " = 0ULL;");
    code.pr("volatile reg_t " + getVarName(GlobalVarType.GLOBAL_OFFSET_INC, null, false) + " = 0ULL;");
    code.pr("const uint64_t " + getVarName(GlobalVarType.GLOBAL_ZERO, null, false) + " = 0ULL;");
    code.pr("const uint64_t " + getVarName(GlobalVarType.GLOBAL_ONE, null, false) + " = 1ULL;");
    code.pr(
        "volatile uint64_t "
            + getVarName(GlobalVarType.WORKER_COUNTER, workers, false)
            + " = {0ULL};"); // Must be uint64_t, otherwise writing a long long to it could cause
    // buffer overflow.
    code.pr("volatile reg_t " + getVarName(GlobalVarType.WORKER_RETURN_ADDR, workers, false) + " = {0ULL};");
    code.pr("volatile reg_t " + getVarName(GlobalVarType.WORKER_BINARY_SEMA, workers, false) + " = {0ULL};");

    // Generate static schedules. Iterate over the workers (i.e., the size
    // of the instruction list).
    for (int worker = 0; worker < instructions.size(); worker++) {
      var schedule = instructions.get(worker);
      code.pr("inst_t schedule_" + worker + "[] = {");
      code.indent();

      for (int j = 0; j < schedule.size(); j++) {
        Instruction inst = schedule.get(j);

        // If there is a label attached to the instruction, generate a comment.
        if (inst.hasLabel()) code.pr("// " + getWorkerLabelString(inst.getLabel(), worker) + ":");

        // Generate code based on opcode
        switch (inst.getOpcode()) {
          case ADD:
            {
              InstructionADD add = (InstructionADD) inst;
              code.pr("// Line " + j + ": " + inst.toString());
              code.pr(
                  "{.opcode="
                      + add.getOpcode()
                      + ", "
                      + ".op1.reg="
                      + "(reg_t*)"
                      + getVarName(add.target, add.targetOwner, true)
                      + ", "
                      + ".op2.reg="
                      + "(reg_t*)"
                      + getVarName(add.source, add.sourceOwner, true)
                      + ", "
                      + ".op3.reg="
                      + "(reg_t*)"
                      + getVarName(add.source2, add.source2Owner, true)
                      + "}"
                      + ",");
              break;
            }
          case ADDI:
            {
              InstructionADDI addi = (InstructionADDI) inst;
              code.pr("// Line " + j + ": " + inst.toString());
              code.pr(
                  "{.opcode="
                      + addi.getOpcode()
                      + ", "
                      + ".op1.reg="
                      + "(reg_t*)"
                      + getVarName(addi.target, addi.targetOwner, true)
                      + ", "
                      + ".op2.reg="
                      + "(reg_t*)"
                      + getVarName(addi.source, addi.sourceOwner, true)
                      + ", "
                      + ".op3.imm="
                      + addi.immediate
                      + "LL"
                      + "}"
                      + ",");
              break;
            }
          case ADV:
            {
              ReactorInstance reactor = ((InstructionADV) inst).reactor;
              GlobalVarType baseTime = ((InstructionADV) inst).baseTime;
              GlobalVarType increment = ((InstructionADV) inst).increment;
              code.pr("// Line " + j + ": " + inst.toString());
              code.pr(
                  "{.opcode="
                      + inst.getOpcode()
                      + ", "
                      + ".op1.imm="
                      + reactors.indexOf(reactor)
                      + ", "
                      + ".op2.reg="
                      + "(reg_t*)"
                      + getVarName(baseTime, worker, true)
                      + ", "
                      + ".op3.reg="
                      + "(reg_t*)"
                      + getVarName(increment, worker, true)
                      + "}"
                      + ",");
              break;
            }
          case ADVI:
            {
              GlobalVarType baseTime = ((InstructionADVI) inst).baseTime;
              Long increment = ((InstructionADVI) inst).increment;
              code.pr("// Line " + j + ": " + inst.toString());
              code.pr(
                  "{.opcode="
                      + inst.getOpcode()
                      + ", "
                      + ".op1.reg="
                      + "(reg_t*)"
                      + getPlaceHolderMacro()
                      + ", "
                      + ".op2.reg="
                      + "(reg_t*)"
                      + getVarName(baseTime, worker, true)
                      + ", "
                      + ".op3.imm="
                      + increment
                      + "LL" // FIXME: Why longlong should be ULL for our type?
                      + "}"
                      + ",");
              break;
            }
          case BEQ:
            {
              InstructionBEQ instBEQ = (InstructionBEQ) inst;
              String rs1Str = getVarName(instBEQ.rs1, worker, true);
              String rs2Str = getVarName(instBEQ.rs2, worker, true);
              Object label = instBEQ.label;
              String labelString = getWorkerLabelString(label, worker);
              code.pr(
                  "// Line "
                      + j
                      + ": "
                      + instBEQ);
              code.pr(
                  "{.opcode="
                      + inst.getOpcode()
                      + ", "
                      + ".op1.reg="
                      + "(reg_t*)"
                      + rs1Str
                      + ", "
                      + ".op2.reg="
                      + "(reg_t*)"
                      + rs2Str
                      + ", "
                      + ".op3.imm="
                      + labelString
                      + "}"
                      + ",");
              break;
            }
          case BGE:
            {
              InstructionBGE instBGE = (InstructionBGE) inst;
              String rs1Str = getVarName(instBGE.rs1, worker, true);
              String rs2Str = getVarName(instBGE.rs2, worker, true);
              Object label = instBGE.label;
              String labelString = getWorkerLabelString(label, worker);
              code.pr(
                  "// Line "
                      + j
                      + ": "
                      + "Branch to "
                      + labelString
                      + " if "
                      + rs1Str
                      + " >= "
                      + rs2Str);
              code.pr(
                  "{.opcode="
                      + inst.getOpcode()
                      + ", "
                      + ".op1.reg="
                      + "(reg_t*)"
                      + rs1Str
                      + ", "
                      + ".op2.reg="
                      + "(reg_t*)"
                      + rs2Str
                      + ", "
                      + ".op3.imm="
                      + labelString
                      + "}"
                      + ",");
              break;
            }
          case BLT:
            {
              InstructionBLT instBLT = (InstructionBLT) inst;
              String rs1Str = getVarName(instBLT.rs1, worker, true);
              String rs2Str = getVarName(instBLT.rs2, worker, true);
              Object label = instBLT.label;
              String labelString = getWorkerLabelString(label, worker);
              code.pr(
                  "// Line "
                      + j
                      + ": "
                      + "Branch to "
                      + labelString
                      + " if "
                      + rs1Str
                      + " < "
                      + rs2Str);
              code.pr(
                  "{.opcode="
                      + inst.getOpcode()
                      + ", "
                      + ".op1.reg="
                      + "(reg_t*)"
                      + rs1Str
                      + ", "
                      + ".op2.reg="
                      + "(reg_t*)"
                      + rs2Str
                      + ", "
                      + ".op3.imm="
                      + labelString
                      + "}"
                      + ",");
              break;
            }
          case BNE:
            {
              InstructionBNE instBNE = (InstructionBNE) inst;
              String rs1Str = getVarName(instBNE.rs1, worker, true);
              String rs2Str = getVarName(instBNE.rs2, worker, true);
              Object label = instBNE.label;
              String labelString = getWorkerLabelString(label, worker);
              code.pr(
                  "// Line "
                      + j
                      + ": "
                      + "Branch to "
                      + labelString
                      + " if "
                      + rs1Str
                      + " != "
                      + rs2Str);
              code.pr(
                  "{.opcode="
                      + inst.getOpcode()
                      + ", "
                      + ".op1.reg="
                      + "(reg_t*)"
                      + rs1Str
                      + ", "
                      + ".op2.reg="
                      + "(reg_t*)"
                      + rs2Str
                      + ", "
                      + ".op3.imm="
                      + labelString
                      + "}"
                      + ",");
              break;
            }
          case DU:
            {
              TimeValue releaseTime = ((InstructionDU) inst).releaseTime;
              code.pr(
                  "// Line "
                      + j
                      + ": "
                      + "Delay Until the variable offset plus "
                      + releaseTime
                      + " is reached.");
              code.pr(
                  "{.opcode="
                      + inst.getOpcode()
                      + ", "
                      + ".op1.reg="
                      + "(reg_t*)"
                      + getVarName(GlobalVarType.GLOBAL_OFFSET, null, true)
                      + ", "
                      + ".op2.imm="
                      + releaseTime.toNanoSeconds()
                      + "LL" // FIXME: LL vs ULL. Since we are giving time in signed ints. Why not
                      // use signed int as our basic data type not, unsigned?
                      + "}"
                      + ",");
              break;
            }
          case EIT:
            {
              ReactionInstance reaction = ((InstructionEIT) inst).reaction;
              code.pr(
                  "// Line "
                      + j
                      + ": "
                      + "Execute reaction "
                      + reaction
                      + " if it is marked as queued by the runtime");
              code.pr(
                  "{.opcode="
                      + inst.getOpcode()
                      + ", "
                      + ".op1.imm="
                      + reactions.indexOf(reaction)
                      + ", "
                      + ".op2.imm="
                      + -1
                      + "}"
                      + ",");
              break;
            }
          case EXE:
            {
              ReactionInstance _reaction = ((InstructionEXE) inst).reaction;
              code.pr("// Line " + j + ": " + "Execute reaction " + _reaction);
              code.pr(
                  "{.opcode="
                      + inst.getOpcode()
                      + ", "
                      + ".op1.reg="
                      + "(reg_t*)"
                      + getPlaceHolderMacro()
                      + "}"
                      + ",");
              break;
            }
          case JAL:
            {
              GlobalVarType retAddr = ((InstructionJAL) inst).retAddr;
              var targetLabel = ((InstructionJAL) inst).targetLabel;
              String targetFullLabel = getWorkerLabelString(targetLabel, worker);
              code.pr("// Line " + j + ": " + inst.toString());
              code.pr(
                  "{.opcode="
                      + inst.getOpcode()
                      + ", "
                      + ".op1.reg="
                      + "(reg_t*)"
                      + getVarName(retAddr, worker, true)
                      + ", "
                      + ".op2.imm="
                      + targetFullLabel
                      + "}"
                      + ",");
              break;
            }
          case JALR:
            {
              GlobalVarType destination = ((InstructionJALR) inst).destination;
              GlobalVarType baseAddr = ((InstructionJALR) inst).baseAddr;
              Long immediate = ((InstructionJALR) inst).immediate;
              code.pr("// Line " + j + ": " + inst.toString());
              code.pr(
                  "{.opcode="
                      + inst.getOpcode()
                      + ", "
                      + ".op1.reg="
                      + "(reg_t*)"
                      + getVarName(destination, worker, true)
                      + ", "
                      + ".op2.reg=" // FIXME: This does not seem right op2 seems to be used as an
                      // immediate...
                      + "(reg_t*)"
                      + getVarName(baseAddr, worker, true)
                      + ", "
                      + ".op3.imm="
                      + immediate
                      + "}"
                      + ",");
              break;
            }
          case STP:
            {
              code.pr("// Line " + j + ": " + "Stop the execution");
              code.pr("{.opcode=" + inst.getOpcode() + "}" + ",");
              break;
            }
          case WLT:
            {
              GlobalVarType variable = ((InstructionWLT) inst).variable;
              int owner = ((InstructionWLT) inst).owner;
              Long releaseValue = ((InstructionWLT) inst).releaseValue;
              code.pr("// Line " + j + ": " + inst.toString());
              code.pr(
                  "{.opcode="
                      + inst.getOpcode()
                      + ", "
                      + ".op1.reg="
                      + "(reg_t*)"
                      + getVarName(variable, owner, true)
                      + ", "
                      + ".op2.imm="
                      + releaseValue
                      + "}"
                      + ",");
              break;
            }
          case WU:
            {
              GlobalVarType variable = ((InstructionWU) inst).variable;
              int owner = ((InstructionWU) inst).owner;
              Long releaseValue = ((InstructionWU) inst).releaseValue;
              code.pr("// Line " + j + ": " + inst.toString());
              code.pr(
                  "{.opcode="
                      + inst.getOpcode()
                      + ", "
                      + ".op1.reg="
                      + "(reg_t*)"
                      + getVarName(variable, owner, true)
                      + ", "
                      + ".op2.imm="
                      + releaseValue
                      + "}"
                      + ",");
              break;
            }
          default:
            throw new RuntimeException("UNREACHABLE: " + inst.getOpcode());
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

    // A function for initializing the non-compile-time constants.
    code.pr("void initialize_static_schedule() {");
    code.indent();
    code.pr("// Fill in placeholders in the schedule.");
    for (int w = 0; w < this.workers; w++) {
      for (var entry : placeholderMaps.get(w).entrySet()) {
        PretVmLabel label = entry.getKey();
        String labelFull = getWorkerLabelString(label, w);
        String varName = entry.getValue();
        code.pr("schedule_" + w + "[" + labelFull + "]" + ".op1.reg = (reg_t*)" + varName + ";");
      }
    }
    code.unindent();
    code.pr("}");

    // Generate a set of push_pop_peek_pqueue helper functions.
    // Information required:
    // 1. Output port's parent reactor

    // 2. Pqueue index (> 0 if multicast)
    int pqueueIndex = 0;  // Assuming no multicast yet.
    // 3. Logical delay of the connection
    // 4. pqueue_heads index
    // 5. Line macros for updating pqueue_heads



    // Print to file.
    try {
      code.writeToFile(file.toString());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Return a C variable name based on the variable type */
  private String getVarName(Object variable, Integer worker, boolean isPointer) {
    if (variable instanceof GlobalVarType type) {
      String prefix = isPointer ? "&" : "";
      switch (type) {
        case GLOBAL_TIMEOUT:
          return prefix + "timeout";
        case GLOBAL_OFFSET:
          return prefix + "time_offset";
        case GLOBAL_OFFSET_INC:
          return prefix + "offset_inc";
        case GLOBAL_ZERO:
          return prefix + "zero";
        case GLOBAL_ONE:
          return prefix + "one";
        case WORKER_COUNTER:
          return prefix + "counters" + "[" + worker + "]";
        case WORKER_RETURN_ADDR:
          return prefix + "return_addr" + "[" + worker + "]";
        case WORKER_BINARY_SEMA:
          return prefix + "binary_sema" + "[" + worker + "]";
        case EXTERN_START_TIME:
          return prefix + "start_time";
        default:
          throw new RuntimeException("UNREACHABLE!");
      }
    } else if (variable instanceof String str) {
      // If this variable comes from the environment, use a placeholder.
      if (placeholderMaps.get(worker).values().contains(variable))
        return getPlaceHolderMacro();
      // Otherwise, return the string.
      return str;
    }
    else throw new RuntimeException("UNREACHABLE!");
    
  }

  /** Return a string of a label for a worker */
  private String getWorkerLabelString(Object label, int worker) {
    if ((label instanceof PretVmLabel) || (label instanceof Phase))
      return "WORKER" + "_" + worker + "_" + label.toString();
    throw new RuntimeException("Label must be either an instance of PretVmLabel or Phase. Received: " + label.getClass().getName());
  }

  /** Pretty printing instructions */
  public void display(PretVmObjectFile objectFile) {
    List<List<Instruction>> instructions = objectFile.getContent();
    for (int i = 0; i < instructions.size(); i++) {
      List<Instruction> schedule = instructions.get(i);
      System.out.println("Worker " + i + ":");
      for (int j = 0; j < schedule.size(); j++) {
        System.out.println(schedule.get(j));
      }
    }
  }

  /**
   * Link multiple object files into a single executable (represented also in an object file class).
   * Instructions are also inserted based on transition guards between fragments. In addition,
   * PREAMBLE and EPILOGUE instructions are inserted here.
   */
  public PretVmExecutable link(List<PretVmObjectFile> pretvmObjectFiles) {

    // Create empty schedules.
    List<List<Instruction>> schedules = new ArrayList<>();
    for (int i = 0; i < workers; i++) {
      schedules.add(new ArrayList<Instruction>());
    }

    // Generate and append the PREAMBLE code.
    List<List<Instruction>> preamble = generatePreamble();
    for (int i = 0; i < schedules.size(); i++) {
      schedules.get(i).addAll(preamble.get(i));
    }

    // Create a queue for storing unlinked object files.
    Queue<PretVmObjectFile> queue = new LinkedList<>();

    // Create a set for tracking state space fragments seen,
    // so that we don't process the same object file twice.
    Set<PretVmObjectFile> seen = new HashSet<>();

    // Start with the first object file, which must not have upstream fragments.
    PretVmObjectFile current = pretvmObjectFiles.get(0);

    // Add the current fragment to the queue.
    queue.add(current);

    // Iterate while there are still object files in the queue.
    while (queue.size() > 0) {

      // Dequeue an object file.
      current = queue.poll();

      // Get the downstream fragments.
      Set<StateSpaceFragment> downstreamFragments = current.getFragment().getDownstreams().keySet();

      // Obtain partial schedules from the current object file.
      List<List<Instruction>> partialSchedules = current.getContent();

      // Append guards for downstream transitions to the partial schedules.
      List<Instruction> defaultTransition = null;
      for (var dsFragment : downstreamFragments) {
        List<Instruction> transition = current.getFragment().getDownstreams().get(dsFragment);
        // Check if a transition is a default transition.
        if (StateSpaceUtils.isDefaultTransition(transition)) {
          defaultTransition = transition;
          continue;
        }
        // Add COPIES of guarded transitions to the partial schedules.
        // They have to be copies since otherwise labels created for different
        // workers will be added to the same instruction object, creating conflicts.
        for (int i = 0; i < workers; i++) {
          partialSchedules.get(i).addAll(transition.stream().map(Instruction::clone).toList());
        }
      }
      // Make sure to have the default transition copies to be appended LAST.
      if (defaultTransition != null) {
        for (int i = 0; i < workers; i++) {
          partialSchedules
              .get(i)
              .addAll(defaultTransition.stream().map(Instruction::clone).toList());
        }
      }

      // Add a label to the first instruction using the exploration phase
      // (INIT, PERIODIC, SHUTDOWN_TIMEOUT, etc.).
      for (int i = 0; i < workers; i++) {
        partialSchedules.get(i).get(0).setLabel(current.getFragment().getPhase().toString());
      }

      // Add the partial schedules to the main schedule.
      for (int i = 0; i < workers; i++) {
        schedules.get(i).addAll(partialSchedules.get(i));
      }

      // Add current to the seen set.
      seen.add(current);

      // Get the object files associated with the downstream fragments.
      Set<PretVmObjectFile> downstreamObjectFiles =
          downstreamFragments.stream()
              .map(StateSpaceFragment::getObjectFile)
              // Filter out null object file since EPILOGUE has a null object file.
              .filter(it -> it != null)
              .collect(Collectors.toSet());

      // Remove object files that have been seen.
      downstreamObjectFiles.removeAll(seen);

      // Add object files related to the downstream fragments to the queue.
      queue.addAll(downstreamObjectFiles);
    }

    // Generate the EPILOGUE code.
    List<List<Instruction>> epilogue = generateEpilogue();
    for (int i = 0; i < schedules.size(); i++) {
      schedules.get(i).addAll(epilogue.get(i));
    }

    // Generate and append the synchronization block.
    List<List<Instruction>> syncBlock = generateSyncBlock();
    for (int i = 0; i < schedules.size(); i++) {
      schedules.get(i).addAll(syncBlock.get(i));
    }

    return new PretVmExecutable(schedules);
  }

  /** Generate the PREAMBLE code. */
  private List<List<Instruction>> generatePreamble() {

    List<List<Instruction>> schedules = new ArrayList<>();
    for (int worker = 0; worker < workers; worker++) {
      schedules.add(new ArrayList<Instruction>());
    }

    for (int worker = 0; worker < workers; worker++) {
      // [ONLY WORKER 0] Configure timeout register to be start_time + timeout.
      if (worker == 0) {
        // Configure offset register to be start_time.
        schedules
            .get(worker)
            .add(
                new InstructionADDI(
                    GlobalVarType.GLOBAL_OFFSET, null, GlobalVarType.EXTERN_START_TIME, null, 0L));
        // Configure timeout if needed.
        if (targetConfig.get(TimeOutProperty.INSTANCE) != null) {
          schedules
              .get(worker)
              .add(
                  new InstructionADDI(
                      GlobalVarType.GLOBAL_TIMEOUT,
                      worker,
                      GlobalVarType.EXTERN_START_TIME,
                      worker,
                      targetConfig.get(TimeOutProperty.INSTANCE).toNanoSeconds()));
        }
        // Update the time increment register.
        schedules
            .get(worker)
            .add(
                new InstructionADDI(
                    GlobalVarType.GLOBAL_OFFSET_INC, null, GlobalVarType.GLOBAL_ZERO, null, 0L));
      }
      // Let all workers go to SYNC_BLOCK after finishing PREAMBLE.
      schedules
          .get(worker)
          .add(new InstructionJAL(GlobalVarType.WORKER_RETURN_ADDR, Phase.SYNC_BLOCK));
      // Give the first PREAMBLE instruction to a PREAMBLE label.
      schedules.get(worker).get(0).setLabel(Phase.PREAMBLE.toString());
    }

    return schedules;
  }

  /** Generate the EPILOGUE code. */
  private List<List<Instruction>> generateEpilogue() {

    List<List<Instruction>> schedules = new ArrayList<>();
    for (int worker = 0; worker < workers; worker++) {
      schedules.add(new ArrayList<Instruction>());
    }

    for (int worker = 0; worker < workers; worker++) {
      Instruction stp = new InstructionSTP();
      stp.setLabel(Phase.EPILOGUE.toString());
      schedules.get(worker).add(stp);
    }

    return schedules;
  }

  /** Generate the synchronization code block. */
  private List<List<Instruction>> generateSyncBlock() {

    List<List<Instruction>> schedules = new ArrayList<>();

    for (int w = 0; w < workers; w++) {

      schedules.add(new ArrayList<Instruction>());

      // Worker 0 will be responsible for changing the global variables while
      // the other workers wait.
      if (w == 0) {

        // Wait for non-zero workers' binary semaphores to be set to 1.
        for (int worker = 1; worker < workers; worker++) {
          schedules.get(w).add(new InstructionWU(GlobalVarType.WORKER_BINARY_SEMA, worker, 1L));
        }

        // Update the global time offset by an increment (typically the hyperperiod).
        schedules
            .get(0)
            .add(
                new InstructionADD(
                    GlobalVarType.GLOBAL_OFFSET,
                    null,
                    GlobalVarType.GLOBAL_OFFSET,
                    null,
                    GlobalVarType.GLOBAL_OFFSET_INC,
                    null));

        // Reset all workers' counters.
        for (int worker = 0; worker < workers; worker++) {
          schedules
              .get(w)
              .add(
                  new InstructionADDI(
                      GlobalVarType.WORKER_COUNTER, worker, GlobalVarType.GLOBAL_ZERO, null, 0L));
        }

        // Advance all reactors' tags to offset + increment.
        for (int j = 0; j < this.reactors.size(); j++) {
          var reactor = this.reactors.get(j);
          var advi = new InstructionADVI(reactor, GlobalVarType.GLOBAL_OFFSET, 0L);
          advi.setLabel("ADVANCE_TAG_FOR_" + reactor.getFullNameWithJoiner("_") + "_" + generateShortUUID());
          placeholderMaps.get(w).put(
            advi.getLabel(),
            getReactorFromEnv(main, reactor));
          schedules.get(w).add(advi);
        }

        // Set non-zero workers' binary semaphores to be set to 0.
        for (int worker = 1; worker < workers; worker++) {
          schedules
              .get(w)
              .add(
                  new InstructionADDI(
                      GlobalVarType.WORKER_BINARY_SEMA,
                      worker,
                      GlobalVarType.GLOBAL_ZERO,
                      null,
                      0L));
        }

        // Jump back to the return address.
        schedules
            .get(0)
            .add(
                new InstructionJALR(
                    GlobalVarType.GLOBAL_ZERO, GlobalVarType.WORKER_RETURN_ADDR, 0L));

      } else {

        // Set its own semaphore to be 1.
        schedules
            .get(w)
            .add(
                new InstructionADDI(
                    GlobalVarType.WORKER_BINARY_SEMA, w, GlobalVarType.GLOBAL_ZERO, null, 1L));

        // Wait for the worker's own semaphore to be less than 1.
        schedules.get(w).add(new InstructionWLT(GlobalVarType.WORKER_BINARY_SEMA, w, 1L));

        // Jump back to the return address.
        schedules
            .get(w)
            .add(
                new InstructionJALR(
                    GlobalVarType.GLOBAL_ZERO, GlobalVarType.WORKER_RETURN_ADDR, 0L));
      }

      // Give the first instruction to a SYNC_BLOCK label.
      schedules.get(w).get(0).setLabel(Phase.SYNC_BLOCK.toString());
    }

    return schedules;
  }

  private boolean hasIsPresentField(TriggerInstance trigger) {
    return (trigger instanceof ActionInstance)
      || (trigger instanceof PortInstance port && port.isInput());
  }

  private String getPlaceHolderMacro() {
    return "PLACEHOLDER";
  }

  /** Generate short UUID to guarantee uniqueness in strings */
  private String generateShortUUID() {
    return UUID.randomUUID().toString().substring(0, 8); // take first 8 characters
  }

  private String getTriggerPresenceFromEnv(ReactorInstance main, TriggerInstance trigger) {
    return CUtil.getEnvironmentStruct(main) + ".pqueue_heads" + "[" + this.triggers.indexOf(trigger) + "]";
  }

  private String getReactionFromEnv(ReactorInstance main, ReactionInstance reaction) {
    return CUtil.getEnvironmentStruct(main) + ".reaction_array" + "[" + this.reactions.indexOf(reaction) + "]";
  }

  private String getReactorFromEnv(ReactorInstance main, ReactorInstance reactor) {
    return CUtil.getEnvironmentStruct(main) + ".reactor_self_array" + "[" + this.reactors.indexOf(reactor) + "]";
  }
}
