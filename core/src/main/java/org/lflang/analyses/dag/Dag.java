package org.lflang.analyses.dag;

import org.lflang.TimeValue;
import org.lflang.generator.ReactionInstance;

import java.util.ArrayList;
import java.io.IOException;

/**
 * Class representing a Directed Acyclic Graph (Dag) as an array of Dag edges 
 * and an array of Dag nodes.
 * The Dag is then used to generate the dependency matrix, useful for the static
 * scheduling. 
 */
public class Dag {
    /**
     * Array of Dag nodes. It has to be an array, not a set, because nodes
     * can be duplicated at different positions. Also because the order helps 
     * with the dependency generation.
     */
    ArrayList<DagNode> dagNodes;

    /** Array of directed edges */
    ArrayList<DagEdge> dagEdges;

    /**
     * Constructor. Simply creates two array lists.
     */ 
    public Dag(){
        this.dagNodes = new ArrayList<DagNode>();
        this.dagEdges = new ArrayList<DagEdge>();
    }

    /**
     * Add a SYNC or DUMMY node
     * @param type should be either DYMMY or SYNC
     * @param timeStep either the time step or the time
     * @return the construted Dag node
     */
    public DagNode addNode(dagNodeType type, TimeValue timeStep) {
        DagNode dagNode = new DagNode(type, timeStep);
        this.dagNodes.add(dagNode);
        return dagNode;
    }

    /**
     * Add a REACTION node
     * @param type should be REACTION
     * @param reactionInstance 
     * @return the construted Dag node
     */
    public DagNode addNode(dagNodeType type, ReactionInstance reactionInstance) {
        DagNode dagNode = new DagNode(type, reactionInstance);
        this.dagNodes.add(dagNode);
        return dagNode;
    }

    /**
     * Add an edge to the Dag, where the parameters are two DagNodes.
     * @param source
     * @param sink
     */
    public void addEdge(DagNode source, DagNode sink) {
        DagEdge dagEdge = new DagEdge(source, sink);
        this.dagEdges.add(dagEdge);
    }

    /**
     * Add an edge to the Dag, where the parameters are the indexes of two
     * DagNodes in the dagNodes array.
     * @param srcNodeId index of the source DagNode
     * @param sinkNodeId index of the sink DagNode
     * @return true, if the indexes exist and the edge is added, false otherwise.
     */
    public boolean addEdge(int srcNodeId, int sinkNodeId) {
        if (srcNodeId < this.dagEdges.size() && sinkNodeId < this.dagEdges.size()) {
            // Get the DagNodes 
            DagNode srcNode = this.dagNodes.get(srcNodeId);
            DagNode sinkNode = this.dagNodes.get(sinkNodeId);
            // Add the edge
            this.addEdge(srcNode, sinkNode);
            return true;
        } 
        return false;
    }

    /**
     * Check if the Dag edge and node lists are empty.
     * @return true, if edge and node arrays are empty, false otherwise
     */
    public boolean isEmpty() {
        if (this.dagEdges.size() == 0 && this.dagNodes.size() == 0)
            return true;
        return false;
    }

    /**
     * Check if the edge already exits, based on the nodes indexes
     * @param srcNodeId index of the source DagNode
     * @param sinkNodeId index of the sink DagNode
     * @return true, if the edge is already in dagEdges array, false otherwise.
     */
    public boolean edgeExists(int srcNodeId, int sinkNodeId) {
        // Get the DagNodes 
        if (srcNodeId < this.dagEdges.size() && sinkNodeId < this.dagEdges.size()) {
            DagNode srcNode = this.dagNodes.get(srcNodeId);
            DagNode sinkNode = this.dagNodes.get(sinkNodeId);
            // Iterate over the dagEdges array
            for (int i = 0; i < this.dagEdges.size(); i++) {
                DagEdge edge = this.dagEdges.get(i);
                if (edge.sourceNode == srcNode && edge.sinkNode == sinkNode) {
                    return true;
                }
            }
        }
        return false;
    }
}