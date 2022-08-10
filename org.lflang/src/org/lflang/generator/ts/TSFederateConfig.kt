package org.lflang.generator.ts

import org.eclipse.emf.common.util.EList
import org.lflang.ASTUtils
import org.lflang.TimeUnit
import org.lflang.TimeValue
import org.lflang.generator.GenerationException
import org.lflang.lf.Preamble
import org.lflang.lf.Time

class TSFederateConfig (
    private val federateId: Int,
    private val rtiHost: String,
    private val rtiPort: Int,
    private val networkMessageActions: List<String>,
    private val networkInputControlReactionsTriggers: List<String>,
    private val dependOnFedIds: List<Int>,
    private val sendsToFedIds: List<Int>,
    private val minOutputDelay: TimeValue?
 ) {
    fun getFederateId() = federateId
    fun getRtiHost() = rtiHost
    fun getRtiPort() = rtiPort
    fun getNetworkMessageActions() = networkMessageActions
    fun getnetworkInputControlReactionsTriggers() = networkInputControlReactionsTriggers
    fun getDependOnFedIds() = dependOnFedIds
    fun getSendsToFedIds() = sendsToFedIds
    fun getMinOutputDelay() = minOutputDelay

    companion object {
        fun createFederateConfig(preambles: EList<Preamble>): TSFederateConfig? {
            var federateConfigMap = HashMap<String, String>()
            for (preamble in preambles) {
                preamble.code.body.split(",").filter { it.isNotEmpty() }.forEach {
                    val keyValue = it.split(":").map { it.trim() }
                    if (keyValue.size != 2) {
                        throw GenerationException("TS Preamble is out of format: $it")
                    }
                    federateConfigMap[keyValue[0]] = keyValue[1]
                }
            }


            if (!federateConfigMap.isEmpty()) {
                if (federateConfigMap.getValue("federated").toBoolean()) {
                    var minOutputDelay: TimeValue? = null

                    if (federateConfigMap.contains("min_output_delay")) {
                        minOutputDelay = ASTUtils.toTimeValue(ASTUtils.toElement(federateConfigMap.getValue("min_output_delay").trim()))
                    }

                    return TSFederateConfig(
                        federateConfigMap.getValue("id").toInt(),
                        federateConfigMap.getValue("host"),
                        federateConfigMap.getValue("port").toInt(),
                        federateConfigMap.getValue("network_message_actions")
                            .removeSurrounding("[", "]")
                            .split(";").map { it.trim() }.filter { it.isNotEmpty() },
                        federateConfigMap.getValue("network_input_control_reactions")
                            .removeSurrounding("[", "]")
                            .split(";").map { it.trim() }.filter { it.isNotEmpty() },
                        federateConfigMap.getValue("depends_on")
                            .removeSurrounding("[", "]")
                            .split(";").map { it.trim() }
                            .filter { it.isNotEmpty() }.map { it.toInt() },
                        federateConfigMap.getValue("sends_to")
                            .removeSurrounding("[", "]")
                            .split(";").map { it.trim() }
                            .filter { it.isNotEmpty() }.map { it.toInt() },
                        minOutputDelay)
                }
            }
            return null
        }
    }
}