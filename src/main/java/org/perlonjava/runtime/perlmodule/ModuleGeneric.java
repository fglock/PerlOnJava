package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.util.regex.Pattern;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * Java replacement for the helpers in Module::Generic 1.7.0's Generic.xs.
 *
 * <p>The original module and XS implementation are copyright Jacques Deguest,
 * DEGUEST Pte. Ltd., and are distributed under the same terms as Perl.</p>
 */
public class ModuleGeneric extends PerlModuleBase {
    private static final Pattern CLASS_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:::[A-Za-z_][A-Za-z0-9_]*)*");

    public ModuleGeneric() {
        super("Module::Generic", false);
    }

    public static void initialize() {
        ModuleGeneric module = new ModuleGeneric();
        try {
            module.registerMethod("_get_args_as_array", null);
            module.registerMethod("_is_array", null);
            module.registerMethod("_is_class_loaded_xs", null);
            module.registerMethod("_is_code", null);
            module.registerMethod("_is_glob", null);
            module.registerMethod("_is_hash", null);
            module.registerMethod("_is_integer", null);
            module.registerMethod("_is_number", null);
            module.registerMethod("_is_object", null);
            module.registerMethod("_is_overloaded", null);
            module.registerMethod("_is_scalar", null);
            module.registerMethod("_obj2h", null);
            module.registerMethod("_refaddr", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Module::Generic method: " + e.getMessage());
        }
    }

    private static RuntimeScalar arg(RuntimeArray args, int index) {
        return args.size() > index ? args.get(index) : new RuntimeScalar();
    }

    private static RuntimeList bool(boolean value) {
        return (value ? scalarTrue : scalarFalse).getList();
    }

    public static RuntimeList _get_args_as_array(RuntimeArray args, int ctx) {
        if (args.size() == 2 && args.get(1).type == ARRAYREFERENCE) {
            return args.get(1).getList();
        }
        RuntimeArray result = new RuntimeArray();
        for (int i = 1; i < args.size(); i++) {
            RuntimeArray.push(result, new RuntimeScalar(args.get(i)));
        }
        return result.createReference().getList();
    }

    public static RuntimeList _is_array(RuntimeArray args, int ctx) {
        return bool(arg(args, 1).type == ARRAYREFERENCE);
    }

    public static RuntimeList _is_class_loaded_xs(RuntimeArray args, int ctx) {
        RuntimeScalar klass = arg(args, 1);
        if (!klass.getDefinedBoolean() || RuntimeScalarType.isReference(klass)) return scalarFalse.getList();
        String name = klass.toString();
        if (!CLASS_NAME.matcher(name).matches()) return scalarFalse.getList();

        String modulePath = name.replace("::", "/") + ".pm";
        RuntimeHash inc = GlobalVariable.getGlobalHash("main::INC");
        if (inc.exists(modulePath).getBoolean()) return scalarTrue.getList();

        RuntimeScalar version = GlobalVariable.globalVariables.get(name + "::VERSION");
        if (version != null && version.getDefinedBoolean()) return scalarTrue.getList();

        RuntimeArray isa = GlobalVariable.globalArrays.get(name + "::ISA");
        if (isa != null && !isa.isEmpty()) return scalarTrue.getList();

        String prefix = name + "::";
        for (String symbol : GlobalVariable.globalCodeRefs.keySet()) {
            if (symbol.startsWith(prefix)
                    && RuntimeCode.isCodeDefined(GlobalVariable.globalCodeRefs.get(symbol))) {
                return scalarTrue.getList();
            }
        }
        return scalarFalse.getList();
    }

    public static RuntimeList _is_code(RuntimeArray args, int ctx) {
        return bool(arg(args, 1).type == CODE);
    }

    public static RuntimeList _is_glob(RuntimeArray args, int ctx) {
        return bool(arg(args, 1).type == GLOBREFERENCE);
    }

    public static RuntimeList _is_hash(RuntimeArray args, int ctx) {
        RuntimeScalar value = arg(args, 1);
        if (value.type != HASHREFERENCE) return scalarFalse.getList();
        String option = args.size() > 2 ? args.get(2).toString() : "";
        boolean strict = option.equals("strict") || option.equals("native");
        return bool(!strict || RuntimeScalarType.blessedId(value) == 0);
    }

    public static RuntimeList _is_integer(RuntimeArray args, int ctx) {
        RuntimeScalar value = arg(args, 1);
        if (!value.getDefinedBoolean() || RuntimeScalarType.isReference(value)) return scalarFalse.getList();
        return bool(value.toString().matches("[+-]?[0-9]+"));
    }

    public static RuntimeList _is_number(RuntimeArray args, int ctx) {
        RuntimeScalar value = arg(args, 1);
        return bool(value.type == INTEGER || value.type == DOUBLE || value.type == BOOLEAN);
    }

    public static RuntimeList _is_object(RuntimeArray args, int ctx) {
        return bool(RuntimeScalarType.blessedId(arg(args, 1)) != 0);
    }

    public static RuntimeList _is_overloaded(RuntimeArray args, int ctx) {
        int blessId = RuntimeScalarType.blessedId(arg(args, 1));
        return bool(blessId != 0 && OverloadContext.prepare(blessId) != null);
    }

    public static RuntimeList _is_scalar(RuntimeArray args, int ctx) {
        RuntimeScalar value = arg(args, 1);
        if (value.type != REFERENCE || !(value.value instanceof RuntimeScalar referent)) {
            return scalarFalse.getList();
        }
        return bool(!RuntimeScalarType.isReference(referent));
    }

    public static RuntimeList _obj2h(RuntimeArray args, int ctx) {
        RuntimeScalar self = arg(args, 0);
        if (self.type == HASHREFERENCE) return self.getList();
        if (self.type == GLOBREFERENCE && self.value instanceof RuntimeGlob glob) {
            return glob.getGlobHash().createReference().getList();
        }
        if (RuntimeScalarType.isReference(self)) {
            return new RuntimeHash().createReference().getList();
        }

        String className = self.toString();
        RuntimeHash result = new RuntimeHash();
        RuntimeScalar debug = GlobalVariable.globalVariables.get(className + "::DEBUG");
        RuntimeScalar verbose = GlobalVariable.globalVariables.get(className + "::VERBOSE");
        RuntimeScalar error = GlobalVariable.globalVariables.get(className + "::ERROR");
        result.put("debug", debug == null ? new RuntimeScalar(0) : new RuntimeScalar(debug));
        result.put("verbose", verbose == null ? new RuntimeScalar(0) : new RuntimeScalar(verbose));
        result.put("error", error == null ? new RuntimeScalar(0) : new RuntimeScalar(error));
        result.setBlessId(NameNormalizer.getBlessId(className));
        return result.createReference().getList();
    }

    public static RuntimeList _refaddr(RuntimeArray args, int ctx) {
        return ScalarUtil.refaddr(new RuntimeArray(arg(args, 1)), ctx);
    }
}
