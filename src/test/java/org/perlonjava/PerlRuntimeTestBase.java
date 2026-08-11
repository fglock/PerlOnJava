package org.perlonjava;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;

/** Keeps one explicit runtime bound for tests that configure global Perl facades. */
public abstract class PerlRuntimeTestBase {
    private PerlRuntime runtime;
    private PerlRuntime.Binding binding;

    @BeforeEach
    protected void bindPerlRuntime() {
        runtime = new PerlRuntime();
        binding = runtime.bind();
    }

    @AfterEach
    protected void unbindPerlRuntime() {
        binding.close();
    }

    protected final PerlRuntime perlRuntime() {
        return runtime;
    }
}
