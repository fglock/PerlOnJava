package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.astnode.IdentifierNode;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.WeakRefRegistry;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * The Builtin class provides functionalities similar to the Perl builtin module.
 */
public class Builtin extends PerlModuleBase {
    private static int lexicalExportCounter = 1;

    /**
     * Constructor initializes the module.
     */
    public Builtin() {
        super("builtin");
    }

    /**
     * Static initializer to set up the module.
     */
    public static void initialize() {
        Builtin builtin = new Builtin();

        // Initialize as an Exporter module
        builtin.initializeExporter();

        // Define EXPORT_OK array with all exportable functions
        builtin.defineExport("EXPORT_OK",
                "is_bool", "true", "false", "weaken", "unweaken", "is_weak",
                "blessed", "refaddr", "reftype", "ceil", "floor",
                "is_tainted", "trim", "indexed", "inf", "nan",
                "created_as_string", "created_as_number", "stringify",
                "export_lexically"
                // TODO "load_module"
        );

        // Define the :5.40 tag bundle
        builtin.defineExportTag("5.40",
                "true", "false", "weaken", "unweaken", "is_weak",
                "blessed", "refaddr", "reftype", "ceil", "floor",
                "is_tainted", "trim", "indexed");

        try {
            builtin.registerMethod("is_bool", "isBoolean", "$");
            builtin.registerMethod("true", "scalarTrue", "");
            builtin.registerMethod("false", "scalarFalse", "");
            builtin.registerMethod("inf", "scalarInf", "");
            builtin.registerMethod("nan", "scalarNan", "");
            builtin.registerMethod("weaken", "weaken", "$");
            builtin.registerMethod("unweaken", "unweaken", "$");
            builtin.registerMethod("is_weak", "isWeak", "$");
            builtin.registerMethod("blessed", "blessed", "$");
            builtin.registerMethod("refaddr", "refaddr", "$");
            builtin.registerMethod("reftype", "reftype", "$");
            builtin.registerMethod("ceil", "ceil", "$");
            builtin.registerMethod("floor", "floor", "$");
            builtin.registerMethod("trim", "trim", "$");
            builtin.registerMethod("is_tainted", "isTainted", "$");
            builtin.registerMethod("indexed", "indexed", "@");
            builtin.registerMethod("created_as_string", "createdAsString", "$");
            builtin.registerMethod("created_as_number", "createdAsNumber", "$");
            builtin.registerMethod("stringify", "stringify", "$");
            builtin.registerMethod("export_lexically", "exportLexically", "@");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Internals method: " + e.getMessage());
        }
    }

    /**
     * Returns true when given a distinguished boolean value, or false if not.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList isBoolean(RuntimeArray args, int ctx) {
        RuntimeScalar res = args.getFirst();
        if (res.type == READONLY_SCALAR) res = (RuntimeScalar) res.value;
        return new RuntimeList(getScalarBoolean(res.type == RuntimeScalarType.BOOLEAN));
    }

    public static RuntimeList scalarTrue(RuntimeArray args, int ctx) {
        return new RuntimeList(scalarTrue);
    }

    public static RuntimeList scalarFalse(RuntimeArray args, int ctx) {
        return new RuntimeList(scalarFalse);
    }

    public static RuntimeList scalarInf(RuntimeArray args, int ctx) {
        return new RuntimeList(new RuntimeScalar(Double.POSITIVE_INFINITY));
    }

    public static RuntimeList scalarNan(RuntimeArray args, int ctx) {
        return new RuntimeList(new RuntimeScalar(Double.NaN));
    }

    public static RuntimeList weaken(RuntimeArray args, int ctx) {
        RuntimeScalar ref = args.get(0);
        WeakRefRegistry.weaken(ref);
        return new RuntimeList();
    }

    public static RuntimeList unweaken(RuntimeArray args, int ctx) {
        RuntimeScalar ref = args.get(0);
        WeakRefRegistry.unweaken(ref);
        return new RuntimeList();
    }

    public static RuntimeList isWeak(RuntimeArray args, int ctx) {
        return ScalarUtil.isweak(args, ctx);
    }

    public static RuntimeList blessed(RuntimeArray args, int ctx) {
        return ScalarUtil.blessed(args, ctx);
    }

    public static RuntimeList refaddr(RuntimeArray args, int ctx) {
        RuntimeScalar ref = args.get(0);
        if (ref.type == READONLY_SCALAR) ref = (RuntimeScalar) ref.value;
        // Return memory address for reference
        RuntimeScalar result;
        switch (ref.type) {
            case REFERENCE, ARRAYREFERENCE, HASHREFERENCE, CODE, GLOB, GLOBREFERENCE:
                result = getScalarInt(System.identityHashCode(ref.value));
                break;
            default:
                result = scalarUndef;
                break;
        }
        return new RuntimeList(result);
    }

    public static RuntimeList reftype(RuntimeArray args, int ctx) {
        RuntimeScalar ref = args.get(0);
        if (ref.type == READONLY_SCALAR) ref = (RuntimeScalar) ref.value;

        // Check if this is a blessed object (class instance)
        // For Perl 5.38+ class syntax, blessed objects should return "OBJECT"
        int blessId = RuntimeScalarType.blessedId(ref);
        if (blessId != 0) {
            // This is a blessed object - return "OBJECT" for class instances
            // This is specifically for the new class syntax
            return new RuntimeList(new RuntimeScalar("OBJECT"));
        }

        // Return reference type in capitals
        String type = switch (ref.type) {
            case REFERENCE -> {
                if (ref.value instanceof RuntimeScalar scalar) {
                    if (scalar.type == READONLY_SCALAR) scalar = (RuntimeScalar) scalar.value;
                    yield switch (scalar.type) {
                        case REFERENCE, ARRAYREFERENCE, HASHREFERENCE, CODE, REGEX -> "REF";
                        case GLOB, GLOBREFERENCE -> "GLOB";
                        default -> "SCALAR";
                    };
                }
                yield "REF";
            }
            case ARRAYREFERENCE -> "ARRAY";
            case HASHREFERENCE -> "HASH";
            case CODE -> "CODE";
            case GLOB, GLOBREFERENCE -> "GLOB";
            case REGEX -> "REGEXP";
            default -> null;
        };

        return new RuntimeList(type != null ? new RuntimeScalar(type) : scalarUndef);
    }

    public static RuntimeList ceil(RuntimeArray args, int ctx) {
        RuntimeScalar num = args.get(0);
        return new RuntimeList(new RuntimeScalar(Math.ceil(num.getDouble())));
    }

    public static RuntimeList floor(RuntimeArray args, int ctx) {
        RuntimeScalar num = args.get(0);
        return new RuntimeList(new RuntimeScalar(Math.floor(num.getDouble())));
    }

    public static RuntimeList trim(RuntimeArray args, int ctx) {
        RuntimeScalar str = args.get(0);
        return new RuntimeList(new RuntimeScalar(str.toString().trim()));
    }

    public static RuntimeList isTainted(RuntimeArray args, int ctx) {
        RuntimeScalar var = args.get(0);
        return new RuntimeList(new RuntimeScalar(var.isTainted()));
    }

    public static RuntimeList indexed(RuntimeArray args, int ctx) {
        RuntimeList result = new RuntimeList();
        for (int i = 0; i < args.size(); i++) {
            result.add(new RuntimeScalar(i));
            result.add(args.get(i));
        }
        return result;
    }

    public static RuntimeList createdAsString(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = args.get(0);
        return new RuntimeList(getScalarBoolean(scalar.isString()));
    }

    public static RuntimeList createdAsNumber(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = args.get(0);
        if (scalar.type == READONLY_SCALAR) scalar = (RuntimeScalar) scalar.value;
        return new RuntimeList(getScalarBoolean(scalar.type == RuntimeScalarType.INTEGER || scalar.type == RuntimeScalarType.DOUBLE));
    }

    public static RuntimeList stringify(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = args.get(0);
        return new RuntimeList(new RuntimeScalar(scalar.toString()));
    }

    public static RuntimeList exportLexically(RuntimeArray args, int ctx) {
        ScopedSymbolTable scope = getCurrentScope();
        if (scope == null) {
            throw new PerlCompilerException("export_lexically can only be called at compile time");
        }
        if (args.size() % 2 != 0) {
            throw new PerlCompilerException("Odd number of elements in export_lexically");
        }

        for (int i = 0; i < args.size(); i += 2) {
            String name = args.get(i).toString();
            RuntimeScalar symbol = args.get(i + 1);
            if (name.isEmpty()) {
                throw new PerlCompilerException("Missing name in export_lexically");
            }

            char sigil = name.charAt(0);
            if (sigil == '$' || sigil == '@' || sigil == '%') {
                exportLexicalVariable(scope, sigil, name.substring(1), symbol);
            } else {
                String subName = sigil == '&' ? name.substring(1) : name;
                exportLexicalSub(scope, subName, symbol);
            }
        }
        return new RuntimeList();
    }

    private static synchronized int nextLexicalExportId() {
        return lexicalExportCounter++;
    }

    private static RuntimeScalar unwrapReadonly(RuntimeScalar value) {
        while (value != null && value.type == READONLY_SCALAR && value.value instanceof RuntimeScalar inner) {
            value = inner;
        }
        return value;
    }

    private static void exportLexicalVariable(ScopedSymbolTable scope, char sigil, String bareName, RuntimeScalar symbol) {
        if (bareName.isEmpty()) {
            throw new PerlCompilerException("Missing name in export_lexically");
        }

        int id = nextLexicalExportId();
        String hiddenPackage = "PerlOnJava::_LEXICAL_EXPORT_" + id;
        String hiddenName = hiddenPackage + "::" + bareName;
        RuntimeScalar unwrapped = unwrapReadonly(symbol);

        switch (sigil) {
            case '$' -> {
                if (unwrapped == null || unwrapped.type != REFERENCE || !(unwrapped.value instanceof RuntimeScalar target)) {
                    throw new PerlCompilerException("Expected SCALAR reference in export_lexically");
                }
                GlobalVariable.globalVariables.put(hiddenName, target);
            }
            case '@' -> {
                if (unwrapped == null || unwrapped.type != ARRAYREFERENCE || !(unwrapped.value instanceof RuntimeArray target)) {
                    throw new PerlCompilerException("Expected ARRAY reference in export_lexically");
                }
                GlobalVariable.globalArrays.put(hiddenName, target);
            }
            case '%' -> {
                if (unwrapped == null || unwrapped.type != HASHREFERENCE || !(unwrapped.value instanceof RuntimeHash target)) {
                    throw new PerlCompilerException("Expected HASH reference in export_lexically");
                }
                GlobalVariable.globalHashes.put(hiddenName, target);
            }
            default -> throw new PerlCompilerException("Unsupported sigil in export_lexically");
        }

        scope.addVariable(sigil + bareName, "our", hiddenPackage, null);
    }

    private static void exportLexicalSub(ScopedSymbolTable scope, String subName, RuntimeScalar symbol) {
        if (subName.isEmpty()) {
            throw new PerlCompilerException("Missing name in export_lexically");
        }

        RuntimeScalar codeScalar = unwrapReadonly(symbol);
        if (codeScalar != null && codeScalar.type == REFERENCE && codeScalar.value instanceof RuntimeScalar target) {
            codeScalar = unwrapReadonly(target);
        }
        if (codeScalar == null || codeScalar.type != CODE) {
            throw new PerlCompilerException("Expected CODE reference in export_lexically");
        }

        int id = nextLexicalExportId();
        String currentPackage = scope.getCurrentPackage();
        String hiddenVarName = "__leximport_" + id + "_" + sanitizeIdentifier(subName);
        String hiddenFullName = currentPackage + "::" + hiddenVarName;

        GlobalVariable.globalVariables.put(hiddenFullName, new RuntimeScalar(codeScalar));

        OperatorNode marker = new OperatorNode("my",
                new OperatorNode("&", new IdentifierNode(subName, -1), -1),
                -1);
        marker.setAnnotation("hiddenVarName", hiddenVarName);
        marker.setAnnotation("declaringPackage", currentPackage);
        if (codeScalar.value instanceof RuntimeCode code && code.prototype != null) {
            marker.setAnnotation("prototype", code.prototype);
        }

        String lexicalKey = "&" + subName;
        if (scope.getVariableIndexInCurrentScope(lexicalKey) >= 0) {
            scope.replaceVariable(lexicalKey, "my", marker);
        } else {
            scope.addVariable(lexicalKey, "my", marker);
        }
    }

    private static String sanitizeIdentifier(String name) {
        StringBuilder result = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            result.append(Character.isLetterOrDigit(ch) || ch == '_' ? ch : '_');
        }
        return result.toString();
    }
}
