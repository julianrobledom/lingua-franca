package org.lflang.target.property;

import java.util.List;

import org.lflang.Target;

public class NoRuntimeValidationProperty extends DefaultBooleanProperty {

    @Override
    public List<Target> supportedTargets() {
        return List.of(Target.CPP);
    }

}