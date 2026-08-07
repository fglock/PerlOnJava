package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.astnode.IdentifierNode;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.util.concurrent.atomic.AtomicLong;

import static org.perlonjava.frontend.parser.SpecialBlockParser.getCompileTimeMutationScope;

/**
 * Java replacement for Exporter::Lexical's two small XS compiler hooks.
 *
 * <p>The native module adds a CODE value to the pad that is currently being
 * compiled. PerlOnJava represents lexical subs as a symbol-table marker plus
 * a hidden scalar containing the CODE value, so the same operation can be
 * expressed directly without emulating Perl's C pad structures.</p>
 */
public class ExporterLexical extends PerlModuleBase {

    public static final String XS_VERSION = "0.02";

    private static final AtomicLong IMPORT_SEQUENCE = new AtomicLong();

    public ExporterLexical() {
        super("Exporter::Lexical", false);
    }

    public static void initialize() {
        ExporterLexical module = new ExporterLexical();
        try {
            module.registerMethod("lexical_import", null);
            module.registerMethod("_lex_stuff", "lexStuff", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize Exporter::Lexical", e);
        }
    }

    /** Add a lexical sub to the innermost scope currently being compiled. */
    public static RuntimeList lexical_import(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new RuntimeException("Usage: Exporter::Lexical::lexical_import(name, coderef)");
        }

        RuntimeScalar code = args.get(1);
        if (code.type != RuntimeScalarType.CODE) {
            throw new RuntimeException("lexical_import requires a code reference");
        }

        ScopedSymbolTable symbolTable = getCompileTimeMutationScope();
        if (symbolTable == null) {
            throw new RuntimeException("lexical_import can only be called at compile time");
        }

        String name = args.get(0).toString();
        String declaringPackage = symbolTable.getCurrentPackage();
        String hiddenName = name + "__leximport_" + IMPORT_SEQUENCE.incrementAndGet();

        OperatorNode inner = new OperatorNode("$", new IdentifierNode(hiddenName, -1), -1);
        OperatorNode declaration = new OperatorNode("my", inner, -1);
        declaration.setAnnotation("hiddenVarName", hiddenName);
        declaration.setAnnotation("declaringPackage", declaringPackage);

        symbolTable.addVariable("$" + hiddenName, "my", inner);
        symbolTable.addVariable("&" + name, "my", declaration);

        // Qualified hidden scalars are how the emitters retain file-scope
        // lexical-sub CODE values created while a BEGIN/use block is running.
        GlobalVariable.getGlobalVariable(declaringPackage + "::" + hiddenName).set(code);
        return new RuntimeList();
    }

    /**
     * Perl's XS implementation inserts a dummy statement to work around an
     * old pad sequence-number bug. PerlOnJava's parser has no equivalent bug,
     * and the caller's following tokens are already available to it.
     */
    public static RuntimeList lexStuff(RuntimeArray args, int ctx) {
        return new RuntimeList();
    }
}
