package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

/** Java replacement for Devel::LexAlias's small XS pad-rebinding primitive. */
public class DevelLexAlias extends PerlModuleBase {
    public DevelLexAlias() {
        super("Devel::LexAlias", false);
    }

    public static void initialize() {
        RuntimeCode.enableLexicalAliasSupport();
        DevelLexAlias module = new DevelLexAlias();
        try {
            module.registerMethod("_lexalias", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing Devel::LexAlias method", e);
        }
    }

    public static RuntimeList _lexalias(RuntimeArray args, int ctx) {
        if (args.size() != 3 || args.get(0).type != RuntimeScalarType.CODE) {
            throw new IllegalArgumentException("Usage: Devel::LexAlias::_lexalias(CODEREF, NAME, REF)");
        }
        RuntimeScalar reference = args.get(2);
        if (!RuntimeScalarType.isReference(reference)
                || !(reference.value instanceof RuntimeBase replacement)) {
            throw new IllegalArgumentException("ref is not a reference");
        }

        RuntimeCode code = (RuntimeCode) args.get(0).value;
        String variableName = args.get(1).toString();
        RuntimeBase activeCell = RuntimeCode.findActiveLexical(code, variableName);
        if (activeCell != null) {
            // JVM local slots cannot be rebound after entry. Updating the live
            // cell gives the active frame the replacement value; pre-call
            // aliases below retain true cell identity.
            activeCell.setFromList(replacement.getList());
        }
        if (code.closedOverVariables != null
                && code.closedOverVariables.containsKey(variableName)) {
            Internals.rebindCapturedVariable(code, variableName, replacement);
            code.closedOverVariables.put(variableName, replacement);
        } else {
            code.setLexicalAlias(variableName, replacement);
        }
        return new RuntimeList();
    }
}
