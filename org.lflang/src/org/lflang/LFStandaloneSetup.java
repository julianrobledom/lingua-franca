/*
 * generated by Xtext 2.25.0
 */

package org.lflang;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import org.eclipse.xtext.util.Modules2;

/**
 * Initialization support for running Xtext languages without
 * Equinox extension registry.
 *
 * See {@link LFRuntimeModule}, the base Guice module for LF services.
 */
public class LFStandaloneSetup extends LFStandaloneSetupGenerated {

    private final Module module;

    public LFStandaloneSetup() {
        this.module = new LFRuntimeModule();
    }

    public LFStandaloneSetup(Module... modules) {
        this.module = Modules2.mixin(modules);
    }

    @Override
    public Injector createInjector() {
        return Guice.createInjector(this.module);
    }
}
