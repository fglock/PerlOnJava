package org.perlonjava.backend.bytecode;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.frontend.astnode.ArgumentLine;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.ListNode;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.JavaClassInfo;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.frontend.semantic.SymbolTable;
import org.perlonjava.runtime.runtimetypes.ErrorMessageUtil;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles parsed format argument expressions into shared interpreter bytecode. */
public final class FormatExpressionCompiler {
    private FormatExpressionCompiler() {}

    public static List<InterpretedCode> compile(ArgumentLine line, String sourceName,
                                                 ErrorMessageUtil errorUtil,
                                                 ScopedSymbolTable scope,
                                                 EmitterContext context) {
        if (line == null || line.getBooleanAnnotation("formatExpressionParseFallback")
                || line.expressions.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> registry = new LinkedHashMap<>();
        if (scope != null) {
            // ScopedSymbolTable indices are frontend identities and start at
            // zero. InterpretedCode reserves registers 0..2 for `this`, `@_`
            // and `wantarray`, so translate visible entries into the captured
            // register range used by withCapturedVars().
            scope.getVisibleVariableRegistry().forEach(
                    (name, index) -> registry.put(name, index + 3));
        }
        Map<String, String> declarations = new LinkedHashMap<>();
        if (scope != null) {
            for (SymbolTable.SymbolEntry entry : scope.getAllVisibleVariables().values()) {
                if (entry.decl() != null) declarations.put(entry.name(), entry.decl());
            }
        }
        Map<String, String> ourPackages = scope == null
                ? Map.of() : scope.getVisibleOurRegistry();

        EmitterContext compileContext = context;
        if (compileContext == null) {
            CompilerOptions options = new CompilerOptions();
            options.fileName = sourceName;
            compileContext = new EmitterContext(new JavaClassInfo(), scope, null, null,
                    RuntimeContextType.LIST, false, errorUtil, options, new RuntimeArray());
        } else {
            compileContext = compileContext.with(RuntimeContextType.LIST);
        }

        List<InterpretedCode> compiled = new ArrayList<>(line.expressions.size());
        for (Node expression : line.expressions) {
            if (expression instanceof ListNode list && !list.parenthesized) {
                for (Node element : list.elements) {
                    compiled.add(compileOne(element, line, sourceName, errorUtil,
                            registry, declarations, ourPackages, compileContext));
                }
            } else {
                compiled.add(compileOne(expression, line, sourceName, errorUtil,
                        registry, declarations, ourPackages, compileContext));
            }
        }
        return List.copyOf(compiled);
    }

    private static InterpretedCode compileOne(Node expression, ArgumentLine line,
                                               String sourceName, ErrorMessageUtil errorUtil,
                                               Map<String, Integer> registry,
                                               Map<String, String> declarations,
                                               Map<String, String> ourPackages,
                                               EmitterContext compileContext) {
        BytecodeCompiler compiler = new BytecodeCompiler(sourceName,
                line.sourceLine, errorUtil, registry, declarations, ourPackages);
        return compiler.compile(expression, compileContext);
    }
}
