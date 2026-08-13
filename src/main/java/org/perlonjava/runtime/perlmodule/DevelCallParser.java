package org.perlonjava.runtime.perlmodule;
import org.perlonjava.runtime.runtimetypes.*;

/** Runtime marker for Devel::CallParser; parser hooks are implemented by BEGINLift. */
public class DevelCallParser extends PerlModuleBase {
    public static final String XS_VERSION = "0.004";
    public DevelCallParser() { super("Devel::CallParser", false); }
    public static void initialize() {
        DevelCallParser m = new DevelCallParser();
        try {
            m.registerMethod("callparser0_h", "empty", null);
            m.registerMethod("callparser1_h", "empty", null);
        } catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
    }
    public static RuntimeList empty(RuntimeArray args, int ctx) { return new RuntimeScalar("").getList(); }
}
