package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/** Java XS implementation of Devel::SlowBless's generation counters. */
public class DevelSlowBless extends PerlModuleBase {

    public static final String XS_VERSION = "0.06";

    public DevelSlowBless() {
        super("Devel::SlowBless", false);
    }

    public static void initialize() {
        DevelSlowBless module = new DevelSlowBless();
        try {
            module.registerMethod("sub_gen", null);
            module.registerMethod("amg_gen", null);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public static RuntimeList sub_gen(RuntimeArray args, int ctx) {
        return new RuntimeScalar(PerlRuntime.current().mroState().subGeneration()).getList();
    }

    public static RuntimeList amg_gen(RuntimeArray args, int ctx) {
        // PL_amagic_generation was removed from Perl in 5.17.1. The XS
        // distribution returns zero on all modern Perls.
        return new RuntimeScalar(0).getList();
    }
}
