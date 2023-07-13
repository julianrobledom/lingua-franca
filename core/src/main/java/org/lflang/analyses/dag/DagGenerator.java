package org.lflang.analyses.dag;

import java.util.ArrayList;
import java.util.stream.Collectors;
import org.lflang.TimeUnit;
import org.lflang.TimeValue;
import org.lflang.analyses.statespace.StateSpaceDiagram;
import org.lflang.analyses.statespace.StateSpaceNode;
import org.lflang.generator.ReactionInstance;
import org.lflang.generator.ReactorInstance;
import org.lflang.generator.c.CFileConfig;

/**
 * Constructs a Directed Acyclic Graph (Dag) from the State Space Diagram. This is part of the
 * static schedule generation.
 *
 * @author Chadlia Jerad
 * @author Shaokai Lin
 */
public class DagGenerator {
  /** The main reactor instance. */
  public ReactorInstance main;

  /** State Space Diagram, to be constructed by explorer() method in StateSpaceExplorer. */
  public StateSpaceDiagram stateSpaceDiagram;

  /** File config */
  public final CFileConfig fileConfig;

  /** The Dag to be contructed. */
  private Dag dag;

  /**
   * Constructor. Sets the main reactor and initializes the dag
   *
   * @param main main reactor instance
   */
  public DagGenerator(
      CFileConfig fileConfig, ReactorInstance main, StateSpaceDiagram stateSpaceDiagram) {
    this.fileConfig = fileConfig;
    this.main = main;
    this.stateSpaceDiagram = stateSpaceDiagram;
    this.dag = new Dag();
  }

  /**
   * Generates the Dag. It starts by calling StateSpaceExplorer to construct the state space
   * diagram. This latter, together with the lf program topology and priorities are used to generate
   * the Dag. Only state space diagrams without loops or without an initialization phase can
   * successfully generate DAGs.
   */
  public void generateDag() {
    // Variables
    StateSpaceNode currentStateSpaceNode = this.stateSpaceDiagram.head;
    TimeValue previousTime = TimeValue.ZERO;
    DagNode previousSync = null;
    int loopNodeReached = 0;
    boolean lastIteration = false;

    // Check if a DAG can be generated for the given state space diagram.
    // Only a diagram without a loop or a loopy diagram without an
    // initialization phase can generate the DAG.

    ArrayList<DagNode> currentReactionNodes = new ArrayList<>();
    ArrayList<DagNode> reactionsUnconnectedToSync = new ArrayList<>();
    ArrayList<DagNode> reactionsUnconnectedToNextInvocation = new ArrayList<>();

    DagNode sync = null; // Local variable for tracking the current SYNC node.
    while (currentStateSpaceNode != null) {
      // Check if the current node is a loop node.
      // The stop condition is when the loop node is encountered the 2nd time.
      if (currentStateSpaceNode == this.stateSpaceDiagram.loopNode) {
        loopNodeReached++;
        if (loopNodeReached >= 2) lastIteration = true;
      }

      // Get the current logical time. Or, if this is the last iteration,
      // set the loop period as the logical time.
      TimeValue time;
      if (!lastIteration) time = currentStateSpaceNode.time;
      else time = new TimeValue(this.stateSpaceDiagram.hyperperiod, TimeUnit.NANO);

      // Add a SYNC node.
      sync = this.dag.addNode(DagNode.dagNodeType.SYNC, time);
      if (this.dag.head == null) this.dag.head = sync;

      // Create DUMMY and Connect SYNC and previous SYNC to DUMMY
      if (!time.equals(TimeValue.ZERO)) {
        TimeValue timeDiff = time.sub(previousTime);
        DagNode dummy = this.dag.addNode(DagNode.dagNodeType.DUMMY, timeDiff);
        this.dag.addEdge(previousSync, dummy);
        this.dag.addEdge(dummy, sync);
      }

      // Do not add more reaction nodes, and add edges
      // from existing reactions to the last node.
      if (lastIteration) {
        for (DagNode n : reactionsUnconnectedToSync) {
          this.dag.addEdge(n, sync);
        }
        break;
      }

      // Add reaction nodes, as well as the edges connecting them to SYNC.
      currentReactionNodes.clear();
      for (ReactionInstance reaction : currentStateSpaceNode.reactionsInvoked) {
        DagNode node = this.dag.addNode(DagNode.dagNodeType.REACTION, reaction);
        currentReactionNodes.add(node);
        this.dag.addEdge(sync, node);
      }

      // Now add edges based on reaction dependencies.
      for (DagNode n1 : currentReactionNodes) {
        for (DagNode n2 : currentReactionNodes) {
          if (n1.nodeReaction.dependentReactions().contains(n2.nodeReaction)) {
            this.dag.addEdge(n1, n2);
          }
        }
      }

      // Create a list of ReactionInstances from currentReactionNodes.
      ArrayList<ReactionInstance> currentReactions =
          currentReactionNodes.stream()
              .map(DagNode::getReaction)
              .collect(Collectors.toCollection(ArrayList::new));

      // If there is a newly released reaction found and its prior
      // invocation is not connected to a downstream SYNC node,
      // connect it to a downstream SYNC node to
      // preserve a deterministic order. In other words,
      // check if there are invocations of the same reaction across two
      // time steps, if so, connect the previous invocation to the current
      // SYNC node.
      //
      // FIXME: This assumes that the (conventional) deadline is the
      // period. We need to find a way to integrate LF deadlines into
      // the picture.
      ArrayList<DagNode> toRemove = new ArrayList<>();
      for (DagNode n : reactionsUnconnectedToSync) {
        if (currentReactions.contains(n.nodeReaction)) {
          this.dag.addEdge(n, sync);
          toRemove.add(n);
        }
      }
      reactionsUnconnectedToSync.removeAll(toRemove);
      reactionsUnconnectedToSync.addAll(currentReactionNodes);

      // Check if there are invocations of reactions from the same reactor
      // across two time steps. If so, connect invocations from the
      // previous time step to those in the current time step, in order to
      // preserve determinism.
      ArrayList<DagNode> toRemove2 = new ArrayList<>();
      for (DagNode n1 : reactionsUnconnectedToNextInvocation) {
        for (DagNode n2 : currentReactionNodes) {
          ReactorInstance r1 = n1.getReaction().getParent();
          ReactorInstance r2 = n2.getReaction().getParent();
          if (r1.equals(r2)) {
            this.dag.addEdge(n1, n2);
            toRemove2.add(n1);
          }
        }
      }
      reactionsUnconnectedToNextInvocation.removeAll(toRemove2);
      reactionsUnconnectedToNextInvocation.addAll(currentReactionNodes);

      // Move to the next state space node.
      currentStateSpaceNode = stateSpaceDiagram.getDownstreamNode(currentStateSpaceNode);
      previousSync = sync;
      previousTime = time;
    }
    // After exiting the while loop, assign the last SYNC node as tail.
    this.dag.tail = sync;
  }

  // A getter for the DAG
  public Dag getDag() {
    return this.dag;
  }
}
