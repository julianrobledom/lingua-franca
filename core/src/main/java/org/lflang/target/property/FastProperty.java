package org.lflang.target.property;

import java.util.List;

import org.lflang.Target;
import org.lflang.TargetConfig;
import org.lflang.lf.Action;
import org.lflang.lf.ActionOrigin;
import org.lflang.lf.KeyValuePair;
import org.lflang.lf.LfPackage.Literals;
import org.lflang.lf.Model;
import org.lflang.lf.Reactor;
import org.lflang.target.property.DefaultBooleanProperty;

import org.lflang.validation.ValidationReporter;

public class FastProperty extends DefaultBooleanProperty {

    @Override
    public List<Target> supportedTargets() {
        return Target.ALL;
    }

    @Override
    public void validate(KeyValuePair pair, Model ast, TargetConfig config, ValidationReporter reporter) {
        if (pair != null) {
            // Check for federated
            for (Reactor reactor : ast.getReactors()) {
                // Check to see if the program has a federated reactor
                if (reactor.isFederated()) {
                    reporter.error(
                        "The fast target property is incompatible with federated programs.",
                        pair,
                        Literals.KEY_VALUE_PAIR__NAME);
                    break;
                }
            }

            // Check for physical actions
            for (Reactor reactor : ast.getReactors()) {
                // Check to see if the program has a physical action in a reactor
                for (Action action : reactor.getActions()) {
                    if (action.getOrigin().equals(ActionOrigin.PHYSICAL)) {
                        reporter.error(
                            "The fast target property is incompatible with physical actions.",
                            pair,
                            Literals.KEY_VALUE_PAIR__NAME);
                        break;
                    }
                }
            }
        }
    }
}