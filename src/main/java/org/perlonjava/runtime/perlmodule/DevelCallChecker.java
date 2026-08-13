package org.perlonjava.runtime.perlmodule;
import org.perlonjava.runtime.runtimetypes.*;

/** Runtime marker for Devel::CallChecker's C compatibility API. */
public class DevelCallChecker extends PerlModuleBase {
    public static final String XS_VERSION = "0.009";
    public DevelCallChecker() { super("Devel::CallChecker", false); }
    public static void initialize() {
        DevelCallChecker m = new DevelCallChecker();
        try { m.registerMethod("callchecker0_h", "empty", null); }
        catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
    }
    public static RuntimeList empty(RuntimeArray args, int ctx) { return new RuntimeScalar("").getList(); }
}
