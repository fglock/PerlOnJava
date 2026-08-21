package org.perlonjava.backend.jvm;

import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.analysis.RegexLiteralAnalyzer;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.perlmodule.Strict;
import org.perlonjava.runtime.regex.RuntimeRegex;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.frontend.parser.StringParser;
import org.perlonjava.runtime.NamedCharacterExpansion;
import org.perlonjava.runtime.NamedCharacterExpansionMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The EmitRegex class is responsible for handling regex-related operations
 * within the code generation process. It provides methods to handle binding
 * and non-binding regex operations, as well as specific regex operations like
 * transliteration and replacement.
 */
public class EmitRegex {
    static void handleRegexCallback(EmitterVisitor emitterVisitor, OperatorNode node) {
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        emitterVisitor.ctx.mv.visitLdcInsn((String) node.getAnnotation("regexCallbackKind"));
        emitterVisitor.ctx.mv.visitLdcInsn((String) node.getAnnotation("regexCallbackPackage"));
        emitterVisitor.ctx.mv.visitLdcInsn((String) node.getAnnotation("regexCallbackSource"));
        emitterVisitor.ctx.mv.visitInsn(Boolean.TRUE.equals(node.getAnnotation(
                "regexCallbackUninitializedWarningsEnabled"))
                ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/regex/RuntimeRegexCallback", "wrap",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                false);
    }

    static void handleRegexTemplate(EmitterVisitor emitterVisitor, OperatorNode node) {
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/regex/RuntimeRegexTemplate", "build",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }
    // Callsite ID counter for /o modifier support (unique across all JVM compilations)
    private static final AtomicInteger nextCallsiteId = new AtomicInteger(100000);

    private static boolean unicodeStringsEnabled(EmitterVisitor emitterVisitor) {
        return emitterVisitor.ctx.symbolTable != null
                && emitterVisitor.ctx.symbolTable.isFeatureCategoryEnabled("unicode_strings");
    }

    /** Stack: ... flags → ... mergedFlags when {@code use feature 'unicode_strings'} is active. */
    private static void maybeApplyUnicodeStringsRegexModifiers(EmitterVisitor emitterVisitor) {
        if (unicodeStringsEnabled(emitterVisitor)) {
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/regex/RuntimeRegex",
                    "applyUnicodeStringsFeatureToModifiers",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                    false);
        }
    }

    /** Emit the lexical warning state captured when the regex was parsed. */
    private static void emitRegexWarningState(EmitterVisitor emitterVisitor, OperatorNode node) {
        boolean enabled = Boolean.TRUE.equals(node.getAnnotation("regexWarningsEnabled"));
        boolean fatal = Boolean.TRUE.equals(node.getAnnotation("regexWarningsFatal"));
        boolean suppressed = Boolean.TRUE.equals(
                node.getAnnotation("regexWarningsSuppressed"));
        emitterVisitor.ctx.mv.visitInsn(suppressed ? Opcodes.ICONST_M1
                : fatal ? Opcodes.ICONST_2
                : enabled ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/regex/RegexQuoteMeta",
                "setCallSiteWarningState", "(I)V", false);
        Object warningBits = node.getAnnotation("regexWarningBits");
        if (warningBits instanceof String bits) {
            emitterVisitor.ctx.mv.visitLdcInsn(bits);
        } else {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.ACONST_NULL);
        }
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/regex/RegexQuoteMeta",
                "setCallSiteWarningBits", "(Ljava/lang/String;)V", false);
    }

    /** Stack: pattern -> pattern carrying this literal's immutable lexical results. */
    private static void attachNamedCharacterExpansions(
            EmitterVisitor emitterVisitor, OperatorNode node, ListNode operand) {
        Object annotated = node.getAnnotation(
                StringParser.LEXICAL_NAMED_CHARACTER_EXPANSIONS);
        if (annotated == null) {
            annotated = operand.getAnnotation(
                    StringParser.LEXICAL_NAMED_CHARACTER_EXPANSIONS);
        }
        if (!(annotated instanceof NamedCharacterExpansionMap metadata)) return;

        emitterVisitor.ctx.mv.visitLdcInsn(metadata.literalIdentity().value());
        emitterVisitor.ctx.mv.visitLdcInsn(metadata.callableIdentity().value());
        emitterVisitor.ctx.mv.visitLdcInsn(metadata.expansions().size() * 7);
        emitterVisitor.ctx.mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
        int index = 0;
        for (var entry : metadata.expansions().entrySet()) {
            NamedCharacterExpansionMap.Key key = entry.getKey();
            NamedCharacterExpansion expansion = entry.getValue();
            String[] fields = {
                    key.sourceSpelling(), key.sourceMode().name(),
                    expansion.sequence(), expansion.sourceMode().name(),
                    Boolean.toString(expansion.promotesUnicode()),
                    expansion.status().name(), expansion.diagnostic()
            };
            for (String field : fields) {
                emitterVisitor.ctx.mv.visitInsn(Opcodes.DUP);
                emitterVisitor.ctx.mv.visitLdcInsn(index++);
                if (field == null) emitterVisitor.ctx.mv.visitInsn(Opcodes.ACONST_NULL);
                else emitterVisitor.ctx.mv.visitLdcInsn(field);
                emitterVisitor.ctx.mv.visitInsn(Opcodes.AASTORE);
            }
        }
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/NamedCharacterExpansionMap", "fromFlat",
                "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Lorg/perlonjava/runtime/NamedCharacterExpansionMap;",
                false);
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/regex/RuntimeRegex",
                "attachNamedCharacterExpansions",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/NamedCharacterExpansionMap;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                false);
    }

    /**
     * Handles the binding regex operation where a variable is bound to a regex operation.
     * This method processes the binary operator node representing the binding operation.
     * Example: $variable =~ /pattern/
     *
     * @param emitterVisitor The visitor used to emit bytecode.
     * @param node           The binary operator node representing the binding regex operation.
     */
    static void handleBindRegex(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);

        if (node.right instanceof OperatorNode right
                && right.operand instanceof ListNode listNode
                && !right.operator.equals("quoteRegex")) {
            // Regex operator: $v =~ /regex/; (but NOT qr//)
            // Bind the variable to the regex operation
            // Do not mutate the original AST: create a local copy of the operator and its operand list.
            ListNode boundListNode = new ListNode(new ArrayList<>(listNode.elements), listNode.tokenIndex);
            boundListNode.handle = listNode.handle;
            if (listNode.annotations != null) {
                boundListNode.annotations = new HashMap<>(listNode.annotations);
            }
            boundListNode.elements.add(node.left);

            OperatorNode boundRight = new OperatorNode(right.operator, boundListNode, right.tokenIndex);
            boundRight.id = right.id;
            if (right.annotations != null) {
                boundRight.annotations = new HashMap<>(right.annotations);
            }
            boundRight.accept(emitterVisitor);  // Use caller's context for regex operations
            return;
        }

        // Handle non-regex operator case (e.g., $v =~ $qr OR $v =~ qr//)
        node.right.accept(scalarVisitor);

        int regexSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledRegex = regexSlot >= 0;
        if (!pooledRegex) {
            regexSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, regexSlot);

        node.left.accept(scalarVisitor);

        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, regexSlot);
        emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);

        if (pooledRegex) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }
        emitMatchRegex(emitterVisitor);  // Use caller's context for regex matching
    }

    /**
     * Handles the non-binding regex operation (!~).
     * Negates the result of a binding regex operation.
     * Example: $variable !~ /pattern/
     *
     * @param emitterVisitor The visitor used to emit bytecode.
     * @param node           The binary operator node representing the non-binding regex operation.
     */
    static void handleNotBindRegex(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // Check if using !~ with tr///r or y///r (which doesn't make sense)
        if (node.right instanceof OperatorNode operatorNode
                && (operatorNode.operator.equals("tr") || operatorNode.operator.equals("transliterate"))
                && operatorNode.operand instanceof ListNode listNode
                && listNode.elements.size() >= 3) {
            // Check if the modifiers (third element) contain 'r'
            Node modifiersNode = listNode.elements.get(2);
            if (modifiersNode instanceof StringNode stringNode) {
                String modifiers = stringNode.value;
                if (modifiers.contains("r")) {
                    throw new PerlCompilerException(node.tokenIndex,
                            "Using !~ with tr///r doesn't make sense",
                            emitterVisitor.ctx.errorUtil);
                }
            }
        }

        // Check if using !~ with s///r (which doesn't make sense)
        if (node.right instanceof OperatorNode operatorNode
                && operatorNode.operator.equals("replaceRegex")
                && operatorNode.operand instanceof ListNode listNode
                && listNode.elements.size() >= 2) {
            // Check if the modifiers (second element) contain 'r'
            Node modifiersNode = listNode.elements.get(1);
            if (modifiersNode instanceof StringNode stringNode) {
                String modifiers = stringNode.value;
                if (modifiers.contains("r")) {
                    throw new PerlCompilerException(node.tokenIndex,
                            "Using !~ with s///r doesn't make sense",
                            emitterVisitor.ctx.errorUtil);
                }
            }
        }

        emitterVisitor.visit(
                new OperatorNode("not",
                        new BinaryOperatorNode(
                                "=~",
                                node.left,
                                node.right,
                                node.tokenIndex
                        ), node.tokenIndex
                ));
    }

    /**
     * Handles system command execution (backticks or qx operator).
     * Example: `command` or qx/command/ or readpipe($expr)
     */
    static void handleSystemCommand(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);
        Node commandNode;

        // Handle two cases:
        // 1. readpipe() with no args -> operand is OperatorNode for $_
        // 2. readpipe($expr) or `cmd` -> operand is ListNode with command
        if (node.operand instanceof ListNode operand) {
            commandNode = operand.elements.getFirst();
        } else {
            // readpipe() with no arguments uses $_
            commandNode = node.operand;
        }

        commandNode.accept(scalarVisitor);
        emitterVisitor.pushCallContext();
        // Create an OperatorNode for systemCommand
        OperatorNode systemCmdNode = new OperatorNode("systemCommand", commandNode, node.tokenIndex);
        EmitOperator.emitOperator(systemCmdNode, emitterVisitor);
    }


    /**
     * Handles transliteration operations (tr/// or y///).
     * Example: $string =~ tr/abc/def/
     */
    static void handleTransliterate(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Defensive: ensure operand is a ListNode
        ListNode operand = (node.operand instanceof ListNode) 
            ? (ListNode) node.operand 
            : ListNode.makeList(node.operand);
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);

        // Process the three required components: source, target, and flags
        operand.elements.get(0).accept(scalarVisitor);  // Source characters
        operand.elements.get(1).accept(scalarVisitor);  // Target characters
        operand.elements.get(2).accept(scalarVisitor);  // Flags/modifiers

        // Compile the transliteration operation
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/operators/RuntimeTransliterate", "compile",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/operators/RuntimeTransliterate;", false);

        // Use default variable $_ if none specified
        handleVariableBinding(operand, 3, scalarVisitor);

        // Push call context for SCALAR or LIST context.
        emitterVisitor.pushCallContext();

        // Execute the transliteration
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/operators/RuntimeTransliterate", "transliterate", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);

        // Clean up stack if in void context
        EmitOperator.handleVoidContext(emitterVisitor);
    }

    /**
     * Handles regex replacement operations (s///).
     * Example: $string =~ s/pattern/replacement/
     */
    static void handleReplaceRegex(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Defensive: ensure operand is a ListNode
        ListNode operand = (node.operand instanceof ListNode) 
            ? (ListNode) node.operand 
            : ListNode.makeList(node.operand);
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);

        // Process pattern, replacement, and flags
        operand.elements.get(0).accept(scalarVisitor);  // Pattern
        operand.elements.get(1).accept(scalarVisitor);  // Replacement
        operand.elements.get(2).accept(scalarVisitor);  // Flags
        maybeApplyUnicodeStringsRegexModifiers(emitterVisitor);
        emitRegexWarningState(emitterVisitor, node);

        // Push the caller's @_ so $_[0] etc. work in s/// replacement
        // @_ is at local variable slot 1 in subroutines
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, 1);

        // Create the replacement regex (4-argument version with caller's @_).
        // Substitution carries lexical `use bytes` on the regex object so the
        // runtime can match a byte view and still write through the original lvalue.
        String replacementFactory = emitterVisitor.ctx.symbolTable != null
                && emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_BYTES)
                ? "getBytesReplacementRegex" : "getReplacementRegex";
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/regex/RuntimeRegex", replacementFactory,
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);

        int regexSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledRegex = regexSlot >= 0;
        if (!pooledRegex) {
            regexSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, regexSlot);

        // Use default variable $_ if none specified
        handleVariableBinding(operand, 3, scalarVisitor);

        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, regexSlot);
        emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);

        if (pooledRegex) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }

        emitMatchRegexWithoutBytesView(emitterVisitor);
    }

    /**
     * Handles quoted regex operations (qr//).
     * Example: qr/pattern/
     */
    static void handleQuoteRegex(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Defensive: ensure operand is a ListNode
        ListNode operand = (node.operand instanceof ListNode) 
            ? (ListNode) node.operand 
            : ListNode.makeList(node.operand);
        validateLiteralRegex(emitterVisitor, node, operand);
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);

        // Process pattern and flags
        operand.elements.get(0).accept(scalarVisitor);  // Pattern
        attachNamedCharacterExpansions(emitterVisitor, node, operand);
        operand.elements.get(1).accept(scalarVisitor);  // Flags
        maybeApplyUnicodeStringsRegexModifiers(emitterVisitor);
        emitRegexWarningState(emitterVisitor, node);
        emitterVisitor.ctx.mv.visitLdcInsn(
                emitterVisitor.ctx.symbolTable.getCurrentPackage());

        // Create the quoted regex
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/regex/RuntimeRegex", "getQuotedRegexInPackage",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
        String markMethod = node.getBooleanAnnotation("syntacticQuoteRegex")
                ? "markSyntacticQuoteConstruction"
                : "markQuoteConstruction";
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/regex/RuntimeRegex", markMethod,
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);

        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    /** Validate non-interpolated qr// at CV compilation, as Perl does. */
    private static void validateLiteralRegex(EmitterVisitor emitterVisitor,
                                             OperatorNode node, ListNode operand) {
        if (node.getBooleanAnnotation("literalSyntaxValidated")) return;
        if (operand.elements.size() < 2
                || !(operand.elements.get(1) instanceof StringNode flags)) {
            return;
        }
        String pattern = RegexLiteralAnalyzer.constantString(operand.elements.get(0));
        if (pattern == null) return;
        String diagnosticPattern = RegexLiteralAnalyzer.constantSourceString(
                operand.elements.get(0));
        String modifiers = flags.value;
        if (unicodeStringsEnabled(emitterVisitor) && !modifiers.contains("u")) {
            modifiers += "u";
        }
        try {
            StringParser.validateLiteralNamedCharacters(
                    operand, pattern, modifiers,
                    diagnosticPattern == null ? pattern : diagnosticPattern);
            Object expansions = operand.getAnnotation(
                    StringParser.LEXICAL_NAMED_CHARACTER_EXPANSIONS);
            if (expansions != null) {
                node.setAnnotation(StringParser.LEXICAL_NAMED_CHARACTER_EXPANSIONS,
                        expansions);
            }
        } catch (PerlCompilerException exception) {
            throw PerlCompilerException.withSourceLocation(node.tokenIndex,
                    exception.getMessage(), emitterVisitor.ctx.errorUtil);
        }
    }

    /**
     * Handles regex match operations (m//).
     * Example: $string =~ m/pattern/
     */
    static void handleMatchRegex(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Defensive: ensure operand is a ListNode
        ListNode operand = (node.operand instanceof ListNode) 
            ? (ListNode) node.operand 
            : ListNode.makeList(node.operand);
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);

        // Check if /o or m?PAT? modifier is present (both need per-callsite caching)
        boolean needsCallsiteCache = false;
        Node flagsNode = operand.elements.get(1);
        if (flagsNode instanceof StringNode) {
            String flags = ((StringNode) flagsNode).value;
            needsCallsiteCache = flags.contains("o") || flags.contains("?");
        }

        // Process pattern and flags
        operand.elements.get(0).accept(scalarVisitor);  // Pattern
        attachNamedCharacterExpansions(emitterVisitor, node, operand);
        flagsNode.accept(scalarVisitor);  // Flags
        maybeApplyUnicodeStringsRegexModifiers(emitterVisitor);
        emitRegexWarningState(emitterVisitor, node);

        // Create the regex matcher (use 3-argument version for /o or m?PAT?)
        if (needsCallsiteCache) {
            int callsiteId = nextCallsiteId.getAndIncrement();
            emitterVisitor.ctx.mv.visitLdcInsn(callsiteId);
            emitterVisitor.ctx.mv.visitLdcInsn(
                    emitterVisitor.ctx.symbolTable.getCurrentPackage());
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/regex/RuntimeRegex", "getQuotedRegexInPackage",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;ILjava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
        } else {
            emitterVisitor.ctx.mv.visitLdcInsn(
                    emitterVisitor.ctx.symbolTable.getCurrentPackage());
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/regex/RuntimeRegex", "getQuotedRegexInPackage",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
        }

        int regexSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledRegex = regexSlot >= 0;
        if (!pooledRegex) {
            regexSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, regexSlot);

        // Use default variable $_ if none specified
        handleVariableBinding(operand, 2, scalarVisitor, true);

        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, regexSlot);
        emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);

        if (pooledRegex) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }

        emitMatchRegex(emitterVisitor);
    }

    /**
     * Helper method to emit bytecode for regex matching operations.
     * Handles different context types (SCALAR, VOID) appropriately.
     * When 'use bytes' is in effect, converts the input string to its
     * UTF-8 byte representation before matching.
     */
    private static void emitMatchRegex(EmitterVisitor emitterVisitor) {
        boolean bytesMode = emitterVisitor.ctx.symbolTable != null
                && emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_BYTES);
        emitMatchRegexRuntimeCall(emitterVisitor, bytesMode ? "matchRegexBytes" : "matchRegex");
    }

    /**
     * Match a substitution regex that already carries its byte-mode view.
     * The original target scalar must remain on the stack so replacement writes
     * through its lvalue rather than through a converted temporary.
     */
    private static void emitMatchRegexWithoutBytesView(EmitterVisitor emitterVisitor) {
        emitMatchRegexRuntimeCall(emitterVisitor, "matchRegex");
    }

    private static void emitMatchRegexRuntimeCall(EmitterVisitor emitterVisitor, String methodName) {
        emitterVisitor.pushCallContext();
        // Invoke the regex matching operation
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/regex/RuntimeRegex",
                methodName,
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;", false);

        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            // Discard result if in void context
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
            return;
        }

        // Handle the result based on context type
        if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            // Convert result to Scalar if in scalar context
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
        }
    }

    /**
     * Handles variable binding for regex operations, using $_ as default if no variable is specified.
     *
     * @param operand       The ListNode containing operation elements
     * @param variableIndex The index where the variable binding should be found in the operand list
     * @param scalarVisitor The visitor used to emit scalar context bytecode
     */
    private static void handleVariableBinding(ListNode operand, int variableIndex, EmitterVisitor scalarVisitor) {
        handleVariableBinding(operand, variableIndex, scalarVisitor, false);
    }

    private static void handleVariableBinding(ListNode operand, int variableIndex,
                                              EmitterVisitor scalarVisitor,
                                              boolean stabilizeLiteral) {
        // Check if a variable was provided in the operand list
        Node variable = null;
        if (operand.elements.size() > variableIndex) {
            variable = operand.elements.get(variableIndex);
        }

        // If no variable was specified, use the default $_ variable
        if (variable == null) {
            variable = new OperatorNode("$", new IdentifierNode("_", operand.tokenIndex), operand.tokenIndex);
        }

        // Generate bytecode for the variable access
        variable.accept(scalarVisitor);
        String targetName = regexTargetName(variable);
        if (targetName == null) {
            scalarVisitor.ctx.mv.visitInsn(Opcodes.ACONST_NULL);
        } else {
            scalarVisitor.ctx.mv.visitLdcInsn(targetName);
        }
        scalarVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/regex/RegexQuoteMeta", "setMatchTargetName",
                "(Ljava/lang/String;)V", false);
        if (stabilizeLiteral && variable instanceof StringNode) {
            scalarVisitor.ctx.mv.visitLdcInsn(nextCallsiteId.getAndIncrement());
            scalarVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/regex/RuntimeRegex", "stabilizeLiteralTarget",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                    false);
        }
    }

    private static String regexTargetName(Node node) {
        if (node instanceof OperatorNode operator
                && operator.operator.equals("$")
                && operator.operand instanceof IdentifierNode identifier) {
            return "$" + identifier.name;
        }
        return null;
    }
}
