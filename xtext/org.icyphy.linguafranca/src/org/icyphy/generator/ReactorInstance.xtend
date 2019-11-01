/** A data structure for a reactor instance. */
// The Lingua-Franca toolkit is is licensed under the BSD 2-Clause License.
// See LICENSE.md file in the top repository directory.
package org.icyphy.generator

import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.LinkedList
import java.util.List
import org.eclipse.emf.common.util.EList
import org.icyphy.linguaFranca.Input
import org.icyphy.linguaFranca.Instantiation
import org.icyphy.linguaFranca.Output
import org.icyphy.linguaFranca.Port
import org.icyphy.linguaFranca.Reaction
import org.icyphy.linguaFranca.VarRef

/** Representation of a runtime instance of a reactor.
 *  @author Edward A. Lee, Marten Lohstroh
 */
class ReactorInstance extends NamedInstance<Instantiation> {
        
    /** Create a runtime instance from the specified definition
     *  and with the specified parent that instantiated it.
     *  This constructor should not be used directly.
     *  Instead, use GeneratorBase.reactorInstanceFactory().
     *  @param instance The Instance statement in the AST.
     *  @param parent The parent.
     *  @param generator The generator creating this instance.
     */
    protected new(Instantiation definition, ReactorInstance parent, GeneratorBase generator) {
        super(definition, parent)
        this.generator = generator
        
        // Record how many times the an instance based on the same
        // has been created on this level of the hierarchy. 
        var count = GeneratorBase.nameRegistry.get(this.prefix -> definition.name); // FIXME: no need to have a list, we could just concat the two strings?
        if (count === null) {
        	count = 0
        }
        this.instantiationOrdinal = count++
        GeneratorBase.nameRegistry.put(this.prefix -> definition.name, this.instantiationOrdinal)
        
        // Record how many new ReactorInstance objects 
        // have been created using the same definition.
        count = ReactorInstance.instanceCounter.get(definition);
        if (count === null) {
        	count = 0
        }
        this.instanceOrdinal = count++
        ReactorInstance.instanceCounter.put(definition, this.instantiationOrdinal)
        
        // Instantiate children for this reactor instance
        for (child : definition.reactorClass.instantiations) {
            var childInstance = generator.reactorInstanceFactory(child, this)
            this.children.add(childInstance)
        }
        
        // Instantiate inputs for this reactor instance
        for (inputDecl : definition.reactorClass.inputs) {
            this.inputs.add(new PortInstance(inputDecl, this))
        }
        
        // Instantiate outputs for this reactor instance
        for (outputDecl : definition.reactorClass.outputs) {
            this.outputs.add(new PortInstance(outputDecl, this))
        }
        
        // Instantiate timers for this reactor instance
        for (timerDecl : definition.reactorClass.timers) {
        	this.timers.add(new TimerInstance(timerDecl, this))
        }
        
        // Instantiate actions for this reactor instance
        for (actionDecl : definition.reactorClass.actions) {
        	this.actions.add(new ActionInstance(actionDecl, this))
        }
        
        // Populate destinations map.
        // Note that this can only happen _after_ the children and 
        // port instances have been created.
        for (connection : definition.reactorClass.connections) {
            var srcInstance = this.getPortInstance(connection.leftPort)
            var dstInstances = this.destinations.get(srcInstance)
            if (dstInstances === null) {
                dstInstances = new HashSet<PortInstance>()
                this.destinations.put(srcInstance, dstInstances)   
            }
            dstInstances.add(this.getPortInstance(connection.rightPort))
        }
        
        // Create the reaction instances in this reactor instance.
        // This also establishes all the implied dependencies.
        // Note that this can only happen _after_ the children and 
        // port instances have been created.
        createReactionInstances()
    }
    
    /** The contained instances, indexed by name. */
    public var HashSet<ReactorInstance> children = new HashSet<ReactorInstance>()

    /** A map from sources to destinations as specified by the connections of this reactor instance. */
    public var HashMap<PortInstance, HashSet<PortInstance>> destinations = new HashMap();

    /** The input port instances belonging to this reactor instance. */    
    public var inputs = new HashSet<PortInstance>    

    /** The output port instances belonging to this reactor instance. */    
    public var outputs = new HashSet<PortInstance>    
    
    public var timers = new HashSet<TimerInstance>
    
    public var actions = new HashSet<ActionInstance>
    
    /** List of reaction instances for this reactor instance. */
    public var List<ReactionInstance> reactionInstances = new LinkedList<ReactionInstance>();
    
    var instanceOrdinal = Integer.MIN_VALUE
    
    var instantiationOrdinal = Integer.MIN_VALUE
    
    static var HashMap<Instantiation, Integer> instanceCounter = new HashMap();
    
    /////////////////////////////////////////////

    /** The generator that created this reactor instance. */
    protected GeneratorBase generator
    
    /////////////////////////////////////////////
    
    /** Create all the reaction instances of this reactor instance
     *  and record the (anti-)dependencies between ports and reactions.
     */
    def createReactionInstances() {
		var reactions = this.definition.reactorClass.reactions
		if (this.definition.reactorClass.reactions !== null) {
			var ReactionInstance previousReaction = null
			for (Reaction reaction : reactions) {
				// Create the reaction instance.
				var reactionInstance = new ReactionInstance(reaction, this)
				// If there is an earlier reaction in this same reactor, then
				// create a link
				// in the dependence graph.
				if (previousReaction !== null) {
					previousReaction.dependentReactions.add(reactionInstance)
					reactionInstance.dependsOnReactions.add(previousReaction)
				}
				previousReaction = reactionInstance;
				// Add the reaction instance to the map of reactions for this
				// reactor.
				this.reactionInstances.add(reactionInstance);

				// Establish (anti-)dependencies based
				// on what reactions use and produce.
				// Only consider inputs and outputs, ignore actions and timers.
				var EList<VarRef> deps = null;
				// First handle dependencies
				if (reaction.getTriggers() !== null) {
					deps = reaction.getTriggers();
				}
				if (reaction.getSources() !== null) {
					if (deps !== null) {
						deps.addAll(reaction.getSources());
					} else {
						deps = reaction.getSources();
					}
				}
				if (deps !== null) {
					for (VarRef dep : deps) {
						if (dep.getVariable() instanceof Port) {
							var PortInstance port = this.getPortInstance(dep)
							port.dependentReactions.add(reactionInstance);
							reactionInstance.dependsOnPorts.add(port);
						}
					}
				}

				// Then handle anti-dependencies
				// If the reaction produces an output from this reactor
				// instance,
				// then create a PortInstance for that port (if it does not
				// already exist)
				// and establish the dependency on that port.
				if (reaction.effects !== null) {
					for (VarRef antidep : reaction.getEffects()) {
						if (antidep.variable instanceof Port) {
							var port = this.getPortInstance(antidep);
							port.dependsOnReactions.add(reactionInstance);
							reactionInstance.dependentPorts.add(port);
						}
					}
				}
			}
		}
	}
    
    
    /** Return the name of this instance. If other instances due to
     *  the same instantiation exist at the same level of hierarchy, 
     *  the name is appended with an additional index between braces 
     *  to disambiguate it from those other instances.
     *  @return The name of this instance.
     */
    override String getName() {
    	if (this.instantiationOrdinal > 0) {
    		this.definition.name + "(" + this.instantiationOrdinal + ")"
    	} else {
    		this.definition.name	
    	}
    }

	def String getInstanceID() {
		this.definition.name.toLowerCase + "_" + this.instanceOrdinal;
	}
	
	def String getInstantiationID() { // FIXME: We probably don't need this. InstantiationOrdinal is only useful for getName (i.e., for pretty printing)
		this.definition.name.toLowerCase + "_" + this.instantiationOrdinal;
	}

    /** Return the instance of a child rector created by the specified
     *  definition or null if there is none.
     *  @param definition The definition of the child reactor ("new" statement).
     *  @return The instance of the child reactor or null if there is no
     *   such "new" statement.
     */
    def getChildReactorInstance(Instantiation definition) {
        for (child : this.children) {
            if (child.definition === definition) {
                return child
            }
        }
        null
    }
     
    /** Given a reference to a port either belongs to this reactor
     *  instance or to a child reactor instance, return the port instance.
     *  Return null if there is no such instance.
     *  This is used for port references that have either the form of
     *  portName or reactorName.portName.
     *  @param reference The port reference.
     *  @return A port instance, or null if there is none.
     */
    def getPortInstance(VarRef reference) {
        if (!(reference.variable instanceof Port)) {
           // Trying to resolve something that is not a port
           return null
        }
        if (reference.container === null) {
            // Handle local reference
            return lookupLocalPort(reference.variable as Port)             
        } else {
             // Handle hierarchical reference
            var containerInstance = this.getChildReactorInstance(reference.container)
            return containerInstance.lookupLocalPort(reference.variable as Port) 
        }
    }
    
     /** Given a port definition, return the port instance
     *  corresponding to that definition, or null if there is
     *  no such instance.
     *  @param port The port definition (a syntactic object in the AST).
     *  @return A port instance, or null if there is none.
     */
    def lookupLocalPort(Port port) {
        // Search one of the inputs and outputs sets.
        var ports = null as HashSet<PortInstance>
        if (port instanceof Input) {
            ports = this.inputs
        } else if (port instanceof Output) {
            ports = this.outputs
        }
        for (portInstance : ports) {
            if (portInstance.definition === port) {
                return portInstance
            }
        }
        null
    }
     
    /** Return the set of all ports that receive data from the 
     *  specified source. This includes inputs and outputs at the same level 
     *  of hierarchy and input ports deeper in the hierarchy.
     *  It does not include inputs or outputs up the hierarchy (i.e., ones
     *  that are reached via any output port that it does return).
     *  @param source An output or input port.
     */    
    def transitiveClosure(PortInstance source) {
        var result = new HashSet<PortInstance>();
        transitiveClosure(source, result);
        result
    }    
     
    /** Add to the destinations hash set all ports that receive data from the 
     *  specified source. This includes inputs and outputs at the same level 
     *  of hierarchy and input ports deeper in the hierarchy.
     *  It does not include inputs or outputs up the hierarchy (i.e., ones
     *  that are reached via any output port that it does return).
     *  
     *  @param destinations The set of destinations to populate.
     */    
    private def void transitiveClosure(PortInstance source, HashSet<PortInstance> destinations) {
        var localDestinations = this.destinations.get(source)
        
        for (destination : localDestinations?:emptyList) {
            destinations.add(destination)
            destination.parent.transitiveClosure(destination, destinations)
        }
    }
    
    def getLocalTriggers() {
    	var LinkedHashSet<TriggerInstance> triggers = new LinkedHashSet()
    	triggers.addAll(this.inputs)
    	triggers.addAll(this.actions)
    	triggers.addAll(this.timers)
    	return triggers
    }
    
    // FIXME: add stuff here to get remote triggers
}