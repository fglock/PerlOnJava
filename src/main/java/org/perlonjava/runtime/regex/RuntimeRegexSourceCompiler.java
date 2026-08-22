package org.perlonjava.runtime.regex;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.WarningBitsRegistry;
import org.perlonjava.backend.bytecode.BytecodeCompiler;
import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.backend.bytecode.InterpreterState;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.JavaClassInfo;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.parser.Parser;
import org.perlonjava.frontend.parser.SpecialBlockParser;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.runtimetypes.ErrorMessageUtil;
import org.perlonjava.runtime.runtimetypes.GlobalContext;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.WarningFlags;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.perlmodule.Strict.HINT_RE_EVAL;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.BYTE_STRING;

/** Compiles executable source introduced by runtime regex interpolation. */
final class RuntimeRegexSourceCompiler {
    private static final ThreadLocal<Integer> RUNTIME_SOURCE_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    private RuntimeRegexSourceCompiler() {}

    static RuntimeScalar compile(RuntimeScalar pattern, String modifiers) {
        return compile(pattern, modifiers, null);
    }

    static RuntimeScalar compile(
            RuntimeScalar pattern, String modifiers,
            String eagerInitialClassDiagnostic) {
        int previousDepth = RUNTIME_SOURCE_DEPTH.get();
        RUNTIME_SOURCE_DEPTH.set(previousDepth + 1);
        try {
            return compileOnce(pattern, modifiers, eagerInitialClassDiagnostic);
        } finally {
            if (previousDepth == 0) {
                RUNTIME_SOURCE_DEPTH.remove();
            } else {
                RUNTIME_SOURCE_DEPTH.set(previousDepth);
            }
        }
    }

    /** Prevent malformed synthetic qr// source from recursively recompiling itself. */
    static boolean isCompilingRuntimeSource() {
        return RUNTIME_SOURCE_DEPTH.get() > 0;
    }

    private static RuntimeScalar compileOnce(
            RuntimeScalar pattern, String modifiers,
            String eagerInitialClassDiagnostic) {
        RuntimeCode owner = RuntimeCode.getActiveCodeAt(0);
        Map<String, RuntimeBase> cells = new LinkedHashMap<>();
        if (owner != null) {
            cells.putAll(RuntimeCode.snapshotActiveLexicals(owner));
        }
        // Eval STRING code enters with its outer lexical cells preloaded in
        // captured registers. Those registers have no declaration opcode in
        // the eval body, so they are not added to the active-frame map. Merge
        // them explicitly before strict-vars validation and callback capture;
        // a live active binding wins whenever both routes provide a cell.
        if (owner instanceof InterpretedCode interpreted
                && interpreted.capturedVars != null) {
            for (Map.Entry<String, Integer> entry
                    : interpreted.variableRegistry.entrySet()) {
                int capturedIndex = entry.getValue() - 3;
                if (capturedIndex >= 0
                        && capturedIndex < interpreted.capturedVars.length
                        && interpreted.capturedVars[capturedIndex] != null) {
                    cells.putIfAbsent(
                            entry.getKey(), interpreted.capturedVars[capturedIndex]);
                }
            }
        }
        Map<String, RuntimeBase> orderedCells = new LinkedHashMap<>();
        for (Map.Entry<String, RuntimeBase> entry : cells.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.length() < 2
                    || "this".equals(name) || "@_".equals(name)
                    || "wantarray".equals(name)) {
                continue;
            }
            orderedCells.put(name, entry.getValue());
        }

        String publicModifiers = modifiers.replace("E", "").replace("T", "")
                .replace(String.valueOf(RuntimeRegex.INTERNAL_DEBUG_COMPILE_MARKER), "")
                .replace(String.valueOf(RuntimeRegex.INTERNAL_DEBUG_EXECUTE_MARKER), "")
                .replace(String.valueOf(RuntimeRegex.INTERNAL_DEBUG_COLOR_MARKER), "")
                .replace(String.valueOf(RuntimeRegex.INTERNAL_DEBUG_PARSE_MARKER), "")
                .replace(String.valueOf(RuntimeRegex.INTERNAL_RE_STRICT_MARKER), "");
        // qr// accepts pattern modifiers, not operation modifiers such as the
        // trailing marker used for m?PAT?. Reapply the complete operation flag
        // set to the compiled qr object below.
        String sourceModifiers = publicModifiers.replaceAll("[gcr?op]", "");
        String source = "qr~" + escapeDelimiter(pattern.toString(), '~')
                + "~" + sourceModifiers;
        // Perl compiles each executable runtime pattern as a distinct eval,
        // so diagnostics and warnings use an independent (eval N) filename.
        String sourceName = RuntimeCode.getNextEvalFilename();
        int sourceLine = 1;
        if (eagerInitialClassDiagnostic != null) {
            throw new PerlCompilerException(eagerInitialClassDiagnostic
                    + " at " + sourceName + " line " + sourceLine + ".\n");
        }

        ScopedSymbolTable savedScope = SpecialBlockParser.getCurrentScope();
        try (PerlLanguageProvider.CompilationLockGuard ignored =
                     PerlLanguageProvider.acquireCompilationLock()) {
            Lexer lexer = new Lexer(source);
            List<LexerToken> tokens = lexer.tokenize();
            ScopedSymbolTable symbolTable = new ScopedSymbolTable();
            symbolTable.enterScope();
            symbolTable.addVariable("this", "", null);
            symbolTable.addVariable("@_", "our", null);
            symbolTable.addVariable("wantarray", "", null);
            // Runtime callback source is an eval at the regex construction
            // site. Preserve that site's complete $^H state so strict subs,
            // vars, refs, bytes, and related lexical policy validate the
            // callback while it is compiled, rather than deferring malformed
            // code until the first match executes.
            symbolTable.setStrictOptions(
                    WarningBitsRegistry.getCallSiteHints() | HINT_RE_EVAL);
            symbolTable.setLexicalRegexDebugFlags(RuntimeRegex.debugMode(modifiers));
            symbolTable.enableLexicalRegexModifiers(
                    publicModifiers.replaceAll("[^imsx]", ""));
            String warningBits = RegexQuoteMeta.getCallSiteWarningBits();
            if (warningBits == null) {
                warningBits = WarningBitsRegistry.getCallSiteBits();
            }
            if (warningBits == null) {
                warningBits = WarningBitsRegistry.getCurrent();
            }
            if (warningBits == null && owner != null) {
                warningBits = RuntimeCode.getWarningBitsForCode(owner);
            }
            WarningFlags.setWarningBitsFromString(symbolTable, warningBits);
            symbolTable.setCurrentPackage(
                    InterpreterState.currentPackage.get().toString(), false);

            Map<String, Integer> registry = new LinkedHashMap<>();
            registry.put("this", 0);
            registry.put("@_", 1);
            registry.put("wantarray", 2);
            int register = 3;
            for (String name : orderedCells.keySet()) {
                symbolTable.addVariable(name, "my", null);
                registry.put(name, register++);
            }

            CompilerOptions options = new CompilerOptions();
            options.fileName = sourceName;
            if (pattern.type == BYTE_STRING) {
                options.isByteStringSource = true;
            } else {
                options.isUnicodeSource = pattern.toString().codePoints()
                        .anyMatch(codePoint -> codePoint > 127);
            }
            ErrorMessageUtil errors = new ErrorMessageUtil(sourceName, tokens);
            EmitterContext context = new EmitterContext(
                    new JavaClassInfo(), symbolTable, null, null,
                    RuntimeContextType.SCALAR, false, errors, options, null);
            SpecialBlockParser.setCurrentScope(symbolTable);
            // The synthetic qr// is an internal carrier for a pattern whose
            // constant segments have already passed through lexical
            // overload::constant qr handling.  Applying the handler again
            // here both diverges from Perl's compile-time timing and can
            // recursively recompile a handler-produced executable string.
            RuntimeHash hints = GlobalVariable.getGlobalHash(
                    GlobalContext.encodeSpecialVar("H"));
            RuntimeScalar savedRegexConstant = hints.elements.remove("qr");
            Node ast;
            try {
                ast = new Parser(context, tokens).parse();
            } finally {
                if (savedRegexConstant != null) {
                    hints.elements.put("qr", savedRegexConstant);
                }
            }
            InterpretedCode code = new BytecodeCompiler(
                    sourceName, sourceLine, errors, registry).compile(ast, context);
            int highestCapturedRegister = 2;
            for (int compiledRegister : code.variableRegistry.values()) {
                highestCapturedRegister = Math.max(
                        highestCapturedRegister, compiledRegister);
            }
            RuntimeBase[] capturedCells =
                    new RuntimeBase[highestCapturedRegister - 2];
            for (Map.Entry<String, RuntimeBase> entry : orderedCells.entrySet()) {
                Integer compiledRegister = code.variableRegistry.get(entry.getKey());
                if (compiledRegister != null && compiledRegister >= 3) {
                    capturedCells[compiledRegister - 3] = entry.getValue();
                }
            }
            code = code.withCapturedVars(capturedCells);
            if (owner != null) {
                code.__SUB__ = owner.__SUB__ != null ? owner.__SUB__ : new RuntimeScalar(owner);
            }

            RuntimeList result = code.apply(new RuntimeArray(), RuntimeContextType.SCALAR);
            RuntimeScalar compiled = result.scalar().propagateTaint(pattern);
            // Pattern modifiers were already parsed by the synthetic qr// source
            // above. Reapplying them through getQuotedRegex() merges flags again;
            // that loses the second 'a' of /aa because ordinary flag merging
            // deduplicates modifier characters. Only operation modifiers belong
            // to the outer runtime regexp operation.
            String operationModifiers = publicModifiers.replaceAll("[nimsxpadeul]", "");
            if (compiled.value instanceof RuntimeRegex && !operationModifiers.isEmpty()) {
                RuntimeScalar unmodified = compiled;
                compiled = RuntimeRegex.getQuotedRegex(
                        unmodified, new RuntimeScalar(operationModifiers)).propagateTaint(pattern);
                if (compiled.value != unmodified.value
                        && unmodified.value instanceof RuntimeRegex temporary) {
                    temporary.releaseExecutableCallbacks();
                }
            }
            return compiled;
        } finally {
            SpecialBlockParser.setCurrentScope(savedScope);
        }
    }

    /** Quote a non-paired qr delimiter without changing regex backslash parity. */
    private static String escapeDelimiter(String pattern, char delimiter) {
        StringBuilder escaped = new StringBuilder(pattern.length());
        int precedingBackslashes = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char current = pattern.charAt(i);
            if (current == delimiter && (precedingBackslashes & 1) == 0) {
                escaped.append('\\');
            }
            escaped.append(current);
            precedingBackslashes = current == '\\'
                    ? precedingBackslashes + 1 : 0;
        }
        return escaped.toString();
    }

    static RuntimeScalar compileTemplate(RuntimeScalar original,
                                         RuntimeRegexTemplate template,
                                         String modifiers) {
        RuntimeRegexTemplate.MaskedCallouts masked = template.maskCallouts();
        String sourceModifiers = modifiers
                .replace(String.valueOf(RuntimeRegex.INTERNAL_DEBUG_COMPILE_MARKER), "")
                .replace(String.valueOf(RuntimeRegex.INTERNAL_DEBUG_EXECUTE_MARKER), "")
                .replace(String.valueOf(RuntimeRegex.INTERNAL_DEBUG_COLOR_MARKER), "")
                .replace(String.valueOf(RuntimeRegex.INTERNAL_DEBUG_PARSE_MARKER), "");
        RuntimeScalar compiled = compile(RuntimeRegexTemplate.patternScalar(
                masked.pattern(), template.byteBackedPattern()), sourceModifiers);
        if (!(compiled.value instanceof RuntimeRegex sourceRegex)) {
            throw new IllegalStateException("runtime regex source did not compile to qr//");
        }

        String executablePattern = RuntimeRegexTemplate.offsetCalloutIds(
                sourceRegex.patternString, template.callbacks().size(),
                sourceRegex.executableCallbacks.size());
        executablePattern = masked.restore(executablePattern);

        List<RuntimeRegexCallback> callbacks = new ArrayList<>(template.callbacks());
        callbacks.addAll(sourceRegex.executableCallbacks);
        RuntimeScalar result = RuntimeRegex.compileExecutableTemplate(
                executablePattern, modifiers, callbacks, original, template.byteBackedPattern());
        if (result.value != sourceRegex) {
            sourceRegex.releaseExecutableCallbacks();
        }
        return result;
    }
}
