package org.perlonjava.runtime.perlmodule;

import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

/**
 * Java-backed XS shim for Sub::Identify.
 */
public class SubIdentify extends PerlModuleBase {
    public static final String XS_VERSION = "0.14";

    public SubIdentify() {
        super("Sub::Identify", false);
    }

    public static void initialize() {
        SubIdentify module = new SubIdentify();
        try {
            module.registerMethod("get_code_info", "getCodeInfo", "$");
            module.registerMethod("get_code_location", "getCodeLocation", "$");
            module.registerMethod("is_sub_constant", "isSubConstant", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Sub::Identify method: " + e.getMessage());
        }
    }

    private static RuntimeCode codeArg(RuntimeArray args) {
        if (args.size() == 0) return null;
        RuntimeScalar scalar = args.get(0);
        if (scalar == null) return null;
        scalar = scalar.scalar();
        if (scalar.type != RuntimeScalarType.CODE || !(scalar.value instanceof RuntimeCode code)) {
            return null;
        }
        return code;
    }

    private static boolean visibleCodeSlotMatches(RuntimeCode code, String fullName) {
        RuntimeScalar slot = GlobalVariable.globalCodeRefs.get(fullName);
        return slot != null
                && slot.type == RuntimeScalarType.CODE
                && slot.value == code
                && (code.defined() || code.isDeclared);
    }

    private static String[] codeInfo(RuntimeCode code) {
        String pkg = code.packageName;
        String name = code.subName;

        if (code.explicitlyRenamed) {
            if (pkg == null || pkg.isEmpty()) pkg = "main";
            if (name == null) name = "";
            return new String[] { pkg, name };
        }

        if (pkg == null || pkg.isEmpty()) pkg = "main";
        if (name == null || name.isEmpty()) {
            return new String[] { pkg, "__ANON__" };
        }

        String fullName = NameNormalizer.normalizeVariableName(name, pkg);
        if (!visibleCodeSlotMatches(code, fullName)) {
            return new String[] { pkg, "__ANON__" };
        }

        return new String[] { pkg, name };
    }

    public static RuntimeList getCodeInfo(RuntimeArray args, int ctx) {
        RuntimeCode code = codeArg(args);
        if (code == null) return new RuntimeList();

        String[] info = codeInfo(code);
        return new RuntimeList(new RuntimeScalar(info[0]), new RuntimeScalar(info[1]));
    }

    public static RuntimeList getCodeLocation(RuntimeArray args, int ctx) {
        RuntimeCode code = codeArg(args);
        if (code == null) return new RuntimeList();

        String file = code.cvStartFile;
        int line = code.cvStartLine;
        if ((line <= 0 || file == null || file.isEmpty()) && code instanceof InterpretedCode interpreted) {
            file = interpreted.sourceName;
            line = interpreted.sourceLine;
        }
        if (line <= 0) return new RuntimeList();
        if (file == null || file.isEmpty()) file = "-e";
        return new RuntimeList(new RuntimeScalar(file), new RuntimeScalar(line));
    }

    public static RuntimeList isSubConstant(RuntimeArray args, int ctx) {
        RuntimeCode code = codeArg(args);
        if (code == null) return new RuntimeScalar(0).getList();

        boolean hasEmptyPrototype = code.prototype != null && code.prototype.isEmpty();
        boolean isConstant = hasEmptyPrototype && (code.constantValue != null || code.isConstantCv);
        return new RuntimeScalar(isConstant ? 1 : 0).getList();
    }
}
