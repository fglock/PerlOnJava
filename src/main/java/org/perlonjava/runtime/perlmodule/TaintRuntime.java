package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.GlobalContext;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/** Java replacement for the four XS primitives in Taint::Runtime. */
public class TaintRuntime extends PerlModuleBase {

    public static final String XS_VERSION = "0.03";

    public TaintRuntime() {
        super("Taint::Runtime", false);
    }

    public static void initialize() {
        TaintRuntime module = new TaintRuntime();
        try {
            module.registerMethod("_taint_start", null);
            module.registerMethod("_taint_stop", null);
            module.registerMethod("_taint_enabled", null);
            module.registerMethod("_tainted", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize Taint::Runtime", e);
        }
    }

    public static RuntimeList _taint_start(RuntimeArray args, int ctx) {
        GlobalContext.setThreadTaintMode(true);
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList _taint_stop(RuntimeArray args, int ctx) {
        GlobalContext.setThreadTaintMode(false);
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList _taint_enabled(RuntimeArray args, int ctx) {
        return new RuntimeScalar(GlobalContext.isTaintModeActive()).getList();
    }

    public static RuntimeList _tainted(RuntimeArray args, int ctx) {
        RuntimeScalar value = new RuntimeScalar("");
        value.tainted = true;
        return value.getList();
    }
}
