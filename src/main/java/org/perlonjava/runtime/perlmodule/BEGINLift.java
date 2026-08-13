package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

/** Java bridge for BEGIN::Lift's compile-time call-parser registration. */
public class BEGINLift extends PerlModuleBase {
    public static final String XS_VERSION = "0.07";

    public BEGINLift() { super("BEGIN::Lift", false); }

    public static void initialize() {
        try {
            java.lang.invoke.MethodHandle mh = RuntimeCode.lookup.findStatic(
                    BEGINLift.class, "installKeywordHandler", RuntimeCode.methodType);
            RuntimeCode code = new RuntimeCode(mh, null, null);
            code.isStatic = true;
            code.packageName = "BEGIN::Lift::Util";
            code.subName = "install_keyword_handler";
            GlobalVariable.getGlobalCodeRef("BEGIN::Lift::Util::install_keyword_handler")
                    .set(new RuntimeScalar(code));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RuntimeList installKeywordHandler(RuntimeArray args, int ctx) {
        if (args.size() != 2 || !(args.get(0).value instanceof RuntimeCode keyword)
                || args.get(1).type != RuntimeScalarType.CODE) {
            throw new IllegalArgumentException("install_keyword_handler expects two CODE references");
        }
        PerlRuntime.current().compilationState.callParserHandlers.put(keyword, args.get(1));
        return new RuntimeList();
    }

    public static RuntimeScalar handlerFor(RuntimeScalar keyword) {
        if (keyword == null || !(keyword.value instanceof RuntimeCode code)) return null;
        return PerlRuntime.current().compilationState.callParserHandlers.get(code);
    }
}
