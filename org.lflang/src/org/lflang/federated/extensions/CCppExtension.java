package org.lflang.federated.extensions;

import java.util.LinkedHashMap;

import org.lflang.federated.generator.FedFileConfig;
import org.lflang.federated.generator.FederateInstance;
import org.lflang.generator.DockerGeneratorBase;
import org.lflang.generator.c.CDockerGenerator;

public class CCppExtension extends CExtension {
    @Override
    protected DockerGeneratorBase newDockerGeneratorInstance(FederateInstance federate) {
        return new CDockerGenerator(true, true, federate.targetConfig);
    }
}
