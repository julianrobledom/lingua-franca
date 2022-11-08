package org.lflang.generator.ts

import org.lflang.ErrorReporter
import org.lflang.TargetConfig
import org.lflang.generator.PrependOperator
import org.lflang.generator.getTargetInitializer
import org.lflang.joinWithLn
import org.lflang.lf.Action
import org.lflang.lf.Parameter
import org.lflang.lf.Reactor
import java.util.*

/**
 * Generator for code in the constructor of reactor class in TypeScript target.
 * Specifically, this generator generates the code for constructor argument,
 * call to the super class constructor. This generator uses other code generators
 * for child reactors, timers, parameters, state variables, actions, ports, connections,
 * and code to register reactions. This generator also generates federate port action
 * registrations.
 */
class TSConstructorGenerator (
    private val tsGenerator: TSGenerator,
    private val errorReporter: ErrorReporter,
    private val reactor : Reactor
) {

    private fun initializeParameter(p: Parameter): String =
        "${p.name}: ${TSTypes.getTargetType(p)} = ${TSTypes.getTargetInitializer(p)}"

    private fun generateConstructorArguments(reactor: Reactor): String {
        val arguments = LinkedList<String>()
        if (reactor.isMain || reactor.isFederated) {
            arguments.add("timeout: TimeValue | undefined = undefined")
            arguments.add("keepAlive: boolean = false")
            arguments.add("fast: boolean = false")
            arguments.add("federationID: string = 'Unidentified Federation'")
        } else {
            arguments.add("parent: __Reactor")
        }

        // For TS, parameters are arguments of the class constructor.
        for (parameter in reactor.parameters) {
            arguments.add(initializeParameter(parameter))
        }

        if (reactor.isMain || reactor.isFederated) {
            arguments.add("success?: () => void")
            arguments.add("fail?: () => void")
        }

        return arguments.joinToString(", \n")
    }

    private fun generateSuperConstructorCall(reactor: Reactor, isFederate: Boolean): String {
        if (reactor.isMain) {
            if (isFederate) {
                return with(PrependOperator) {
                    """
                    |        var federateConfig = defaultFederateConfig;
                    |        if (__timeout !== undefined) {
                    |            federateConfig.executionTimeout = __timeout;
                    |        }
                    |        federateConfig.federationID = __federationID;
                    |        federateConfig.fast = __fast;
                    |        federateConfig.keepAlive = __keepAlive;
                    |        super(federateConfig, success, fail);
                    """.trimMargin()
                }
            } else {
                return "super(timeout, keepAlive, fast, success, fail);"
            }
        }
        else {
            return "super(parent);"
        }
    }

    // If the app is federated, register its
    // networkMessageActions with the RTIClient
    private fun generateFederatePortActionRegistrations(networkMessageActions: List<String>): String {
        val connectionInstantiations = LinkedList<String>()
        for ((fedPortID, actionName) in networkMessageActions.withIndex()) {
            val registration = """
                this.registerFederatePortAction(${fedPortID}, this.${actionName});
                """
            connectionInstantiations.add(registration)
        }
        return connectionInstantiations.joinToString("\n")
    }

    // Generate code for setting target configurations.
    private fun generateTargetConfigurations(targetConfig: TargetConfig): String {
        val targetConfigurations = LinkedList<String>()
        if ((reactor.isMain) &&
            targetConfig.coordinationOptions.advance_message_interval != null) {
            targetConfigurations.add("this.setAdvanceMessageInterval(${targetConfig.coordinationOptions.advance_message_interval.toTsTime()})")
        }
        return targetConfigurations.joinToString("\n")
    }

    fun generateConstructor(
        targetConfig: TargetConfig,
        instances: TSInstanceGenerator,
        timers: TSTimerGenerator,
        parameters: TSParameterGenerator,
        states: TSStateGenerator,
        actions: TSActionGenerator,
        ports: TSPortGenerator,
        isFederate: Boolean,
        networkMessageActions: List<String>
    ): String {
        val connections = TSConnectionGenerator(reactor.connections, errorReporter)
        val reactions = TSReactionGenerator(errorReporter, reactor)

        return with(PrependOperator) {
            """
                |constructor (
            ${" |    "..generateConstructorArguments(reactor)}
                |) {
            ${" |    "..generateSuperConstructorCall(reactor, isFederate)}
            ${" |    "..generateTargetConfigurations(targetConfig)}
            ${" |    "..instances.generateInstantiations()}
            ${" |    "..timers.generateInstantiations()}
            ${" |    "..parameters.generateInstantiations()}
            ${" |    "..states.generateInstantiations()}
            ${" |    "..actions.generateInstantiations()}
            ${" |    "..ports.generateInstantiations()}
            ${" |    "..connections.generateInstantiations()}
            ${" |    "..if (reactor.isMain && isFederate) generateFederatePortActionRegistrations(networkMessageActions) else ""}
            ${" |    "..reactions.generateAllReactions()}
                |}
            """.trimMargin()
        }
    }
}
