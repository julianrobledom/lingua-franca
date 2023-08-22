package org.lflang.analyses.statespace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.lflang.analyses.pretvm.GlobalVarType;
import org.lflang.analyses.pretvm.Instruction;
import org.lflang.analyses.pretvm.InstructionJAL;
import org.lflang.analyses.statespace.StateSpaceExplorer.Phase;

/**
 * A utility class for state space-related methods
 *
 * @author Shaokai Lin
 */
public class StateSpaceUtils {

  /**
   * Identify an initialization phase and a periodic phase of the state space diagram, and create
   * two different state space fragments.
   */
  public static ArrayList<StateSpaceFragment> fragmentizeInitAndPeriodic(
      StateSpaceDiagram stateSpace) {

    ArrayList<StateSpaceFragment> fragments = new ArrayList<>();
    StateSpaceNode current = stateSpace.head;
    StateSpaceNode previous = null;

    // Create an initialization phase fragment.
    if (stateSpace.head != stateSpace.loopNode) {
      StateSpaceDiagram initPhase = new StateSpaceDiagram();
      initPhase.head = current;
      while (current != stateSpace.loopNode) {
        // Add node and edges to fragment.
        initPhase.addNode(current);
        initPhase.addEdge(current, previous);

        // Update current and previous pointer.
        previous = current;
        current = stateSpace.getDownstreamNode(current);
      }
      initPhase.tail = previous;
      if (stateSpace.loopNode != null)
        initPhase.hyperperiod = stateSpace.loopNode.getTime().toNanoSeconds();
      else initPhase.hyperperiod = 0;
      initPhase.phase = Phase.INIT;
      fragments.add(new StateSpaceFragment(initPhase));
    }

    // Create a periodic phase fragment.
    if (stateSpace.isCyclic()) {

      // State this assumption explicitly.
      assert current == stateSpace.loopNode : "Current is not pointing to loopNode.";

      StateSpaceDiagram periodicPhase = new StateSpaceDiagram();
      periodicPhase.head = current;
      periodicPhase.addNode(current); // Add the first node.
      if (current == stateSpace.tail) {
        periodicPhase.addEdge(current, current); // Add edges to fragment.
      }
      while (current != stateSpace.tail) {
        // Update current and previous pointer.
        // We bring the updates before addNode() because
        // we need to make sure tail is added.
        // For the init. fragment, we do not want to add loopNode.
        previous = current;
        current = stateSpace.getDownstreamNode(current);

        // Add node and edges to fragment.
        periodicPhase.addNode(current);
        periodicPhase.addEdge(current, previous);
      }
      periodicPhase.tail = current;
      periodicPhase.loopNode = stateSpace.loopNode;
      periodicPhase.addEdge(periodicPhase.loopNode, periodicPhase.tail); // Add loop.
      periodicPhase.loopNodeNext = stateSpace.loopNodeNext;
      periodicPhase.hyperperiod = stateSpace.hyperperiod;
      periodicPhase.phase = Phase.PERIODIC;
      fragments.add(new StateSpaceFragment(periodicPhase));
    }

    // If there are exactly two fragments (init and periodic),
    // make fragments refer to each other.
    if (fragments.size() == 2) connectFragmentsDefault(fragments.get(0), fragments.get(1));

    // If the last fragment is periodic, make it transition back to itself.
    StateSpaceFragment lastFragment = fragments.get(fragments.size() - 1);
    if (lastFragment.getPhase() == Phase.PERIODIC)
      connectFragmentsDefault(lastFragment, lastFragment);

    assert fragments.size() <= 2 : "More than two fragments detected!";
    return fragments;
  }

  /**
   * Connect two fragments with a default transition (no guards). Changing the default transition
   * here would require changing isDefaultTransition() also.
   */
  public static void connectFragmentsDefault(
      StateSpaceFragment upstream, StateSpaceFragment downstream) {
    List<Instruction> defaultTransition =
        Arrays.asList(
            new InstructionJAL(
                GlobalVarType.WORKER_RETURN_ADDR, downstream.getPhase())); // Default transition
    upstream.addDownstream(downstream, defaultTransition);
    downstream.addUpstream(upstream);
  }

  /** Connect two fragments with a guarded transition. */
  public static void connectFragmentsGuarded(
      StateSpaceFragment upstream,
      StateSpaceFragment downstream,
      List<Instruction> guardedTransition) {
    upstream.addDownstream(downstream, guardedTransition);
    downstream.addUpstream(upstream);
  }

  /** Check if a transition is a default transition. */
  public static boolean isDefaultTransition(List<Instruction> transition) {
    return transition.size() == 1 && (transition.get(0) instanceof InstructionJAL);
  }
}
