package org.perlonjava.backend.jvm;

import org.perlonjava.app.cli.CompilerOptions;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.analysis.ReturnTypeVisitor;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.perlmodule.Strict.HINT_STRICT_SUBS;
import static org.perlonjava.runtime.perlmodule.Strict.HINT_UTF8;
import static org.perlonjava.runtime.runtimetypes.ScalarUtils.isInteger;

/**
 * Handles bytecode generation for literal values in the Perl-to-Java compiler.
 *
 * <p>This class provides static methods to emit JVM bytecode for various Perl literal types
 * including strings, numbers, arrays, hashes, lists, and bareword identifiers. It handles
 * context-sensitive code generation, optimizing for void, scalar, and list contexts.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Context-aware code generation (void, scalar, list)</li>
 *   <li>Optimized bytecode for void context to avoid unnecessary object creation</li>
 *   <li>Support for boxed and unboxed numeric values</li>
 *   <li>Proper handling of Perl's context propagation rules</li>
 *   <li>Type-specific optimizations using direct method calls instead of interface dispatch</li>
 * </ul>
 *
 * @see EmitterVisitor
 * @see RuntimeContextType
 */
public class EmitLiteral {

    /**
     * Emits bytecode for a Perl array literal (e.g., [1, 2, 3]).
     *
     * <p>Array literals in Perl always evaluate their elements in LIST context,
     * regardless of the context in which the array literal itself appears.
     * This method handles the following contexts:</p>
     * <ul>
     *   <li>VOID: Elements are evaluated for side effects only, no array is created</li>
     *   <li>SCALAR/LIST: A RuntimeArray is created and populated, then converted to a reference</li>
     * </ul>
     *
     * @param emitterVisitor The visitor context for emitting bytecode
     * @param node           The ArrayLiteralNode representing the array literal in the AST
     */
    public static void emitArrayLiteral(EmitterVisitor emitterVisitor, ArrayLiteralNode node) {
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(ArrayLiteralNode) in context " + emitterVisitor.ctx.contextType);
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Perl semantics: array literal elements are always evaluated in LIST context
        EmitterVisitor elementContext = emitterVisitor.with(RuntimeContextType.LIST);

        // Optimization: In VOID context, evaluate elements for side effects only
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            for (Node element : node.elements) {
                element.accept(elementContext);
                // Pop the list result since we don't need it
                mv.visitInsn(Opcodes.POP);
            }
            if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(ArrayLiteralNode) end");
            return;
        }

        // Create a new RuntimeArray instance
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeArray");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/runtimetypes/RuntimeArray", "<init>", "()V", false);
        // Stack: [RuntimeArray]

        JavaClassInfo.SpillRef arrayRef = emitterVisitor.ctx.javaClassInfo.acquireSpillRefOrAllocate(emitterVisitor.ctx.symbolTable);
        emitterVisitor.ctx.javaClassInfo.storeSpillRef(mv, arrayRef);
        // Stack: []

        // Suppress MortalList.flush() during element evaluation. Without this,
        // a pending mortal decrement on an earlier-added element (e.g., a
        // blessed return value from `Foo->new(...)`) can fire during a later
        // element's interior assignment (`$s->{CHILD} = Bar->new()` inside
        // another new()), prematurely DESTROY'ing it before
        // createReferenceWithTrackedElements finalizes ownership. The
        // wasFlushing flag is stashed in a local so we can restore it at
        // the end. See dev/sandbox tt_arr2.pl for a minimal repro.
        JavaClassInfo.SpillRef wasFlushingRef = emitterVisitor.ctx.javaClassInfo.acquireSpillRefOrAllocate(emitterVisitor.ctx.symbolTable);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/MortalList", "suppressFlush", "(Z)Z", false);
        // Box boolean to store in Object-typed spill slot
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        emitterVisitor.ctx.javaClassInfo.storeSpillRef(mv, wasFlushingRef);

        // Populate the array with elements
        for (Node element : node.elements) {
            // Generate code for the element in LIST context
            element.accept(elementContext);
            JavaClassInfo.SpillRef elementRef = emitterVisitor.ctx.javaClassInfo.acquireSpillRefOrAllocate(emitterVisitor.ctx.symbolTable);
            emitterVisitor.ctx.javaClassInfo.storeSpillRef(mv, elementRef);

            emitterVisitor.ctx.javaClassInfo.loadSpillRef(mv, arrayRef);
            emitterVisitor.ctx.javaClassInfo.loadSpillRef(mv, elementRef);
            emitterVisitor.ctx.javaClassInfo.releaseSpillRef(elementRef);

            // Add the element to the array
            addElementToArray(mv, element);
            // Stack: []
        }

        emitterVisitor.ctx.javaClassInfo.loadSpillRef(mv, arrayRef);
        emitterVisitor.ctx.javaClassInfo.releaseSpillRef(arrayRef);

        // Convert the array to a reference (array literals produce references)
        // Use createReferenceWithTrackedElements to increment refCounts for elements,
        // preventing premature destruction of referents stored in anonymous arrays.
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                "createReferenceWithTrackedElements", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);

        // Restore previous flush-suppression state. Element refCounts have now
        // been bumped by createReferenceWithTrackedElements, so it is safe for
        // pending mortal decrements to fire.
        emitterVisitor.ctx.javaClassInfo.loadSpillRef(mv, wasFlushingRef);
        emitterVisitor.ctx.javaClassInfo.releaseSpillRef(wasFlushingRef);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/MortalList", "suppressFlush", "(Z)Z", false);
        mv.visitInsn(Opcodes.POP);  // discard the return value

        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(ArrayLiteralNode) end");
    }

    /**
     * Emits bytecode for a Perl hash literal (e.g., {a => 1, b => 2}).
     *
     * <p>Hash literals in Perl always evaluate their elements in LIST context,
     * similar to array literals. The elements are collected into a list which
     * is then used to construct the hash.</p>
     *
     * <p>Context handling:</p>
     * <ul>
     *   <li>VOID: Elements are evaluated for side effects only, no hash is created</li>
     *   <li>SCALAR/LIST: Elements are collected into a RuntimeList, then converted to a hash reference</li>
     * </ul>
     *
     * @param emitterVisitor The visitor context for emitting bytecode
     * @param node           The HashLiteralNode representing the hash literal in the AST
     */
    public static void emitHashLiteral(EmitterVisitor emitterVisitor, HashLiteralNode node) {
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(HashLiteralNode) in context " + emitterVisitor.ctx.contextType);
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Optimization: In VOID context, evaluate elements for side effects only
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            EmitterVisitor elementContext = emitterVisitor.with(RuntimeContextType.LIST);
            for (Node element : node.elements) {
                element.accept(elementContext);
                // Pop the list result since we don't need it
                mv.visitInsn(Opcodes.POP);
            }
            if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(HashLiteralNode) end");
            return;
        }

        // Suppress MortalList.flush() during element evaluation — see
        // emitArrayLiteral above for rationale (same issue affects hash
        // literals whose values are blessed temps from method calls).
        JavaClassInfo.SpillRef wasFlushingRef = emitterVisitor.ctx.javaClassInfo.acquireSpillRefOrAllocate(emitterVisitor.ctx.symbolTable);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/MortalList", "suppressFlush", "(Z)Z", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        emitterVisitor.ctx.javaClassInfo.storeSpillRef(mv, wasFlushingRef);

        // Create a RuntimeList from the hash elements
        // This delegates to emitList which handles the LIST context properly
        ListNode listNode = new ListNode(node.elements, node.tokenIndex);
        listNode.accept(emitterVisitor.with(RuntimeContextType.LIST));

        // Convert the list to a hash reference
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/RuntimeHash",
                "createHashRef",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);

        // Restore previous flush-suppression state.
        // Stack: [ref]
        emitterVisitor.ctx.javaClassInfo.loadSpillRef(mv, wasFlushingRef);
        emitterVisitor.ctx.javaClassInfo.releaseSpillRef(wasFlushingRef);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/MortalList", "suppressFlush", "(Z)Z", false);
        mv.visitInsn(Opcodes.POP);  // discard the return value; ref remains on stack

        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(HashLiteralNode) end");
    }

    /**
     * Emits bytecode for a string literal.
     *
     * <p>Handles both regular strings and v-strings (version strings).
     * The method supports both boxed (RuntimeScalar) and unboxed (Java String) contexts.</p>
     *
     * <p>Special handling:</p>
     * <ul>
     *   <li>VOID context: No code is generated</li>
     *   <li>Boxed context: Creates a RuntimeScalar object</li>
     *   <li>Unboxed context: Pushes the raw Java string</li>
     *   <li>V-strings: Sets the appropriate type flag on the RuntimeScalar</li>
     * </ul>
     *
     * @param ctx  The emission context containing method visitor and context information
     * @param node The StringNode containing the string value and metadata
     */
    public static void emitString(EmitterContext ctx, StringNode node) {
        // Skip code generation in void context
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }

        MethodVisitor mv = ctx.mv;

        if (!ctx.isBoxed) {
            // Unboxed context: push the string value
            emitStringValue(mv, node.value);
            return;
        }

        if (node.isVString) {
            // V-strings: no caching (they are rare)
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeScalarReadOnly");
            mv.visitInsn(Opcodes.DUP);
            emitStringValue(mv, node.value);
            mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalarReadOnly",
                    "<init>",
                    "(Ljava/lang/String;)V",
                    false);

            // Set the Perl scalar type to VSTRING
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(RuntimeScalarType.VSTRING);
            mv.visitFieldInsn(Opcodes.PUTFIELD, "org/perlonjava/runtime/runtimetypes/RuntimeScalarReadOnly", "type", "I");
            return;
        }

        if (!ctx.symbolTable.isStrictOptionEnabled(HINT_UTF8) && !ctx.compilerOptions.isUnicodeSource) {
            // Under `no utf8` - create an octet string, unless it contains wide characters (> 255)
            // Wide characters (like \x{100}) force the string to be UTF-8 even without `use utf8`
            boolean hasWideChars = false;
            for (int i = 0; i < node.value.length(); i++) {
                if (node.value.charAt(i) > 255) {
                    hasWideChars = true;
                    break;
                }
            }
            
            if (!hasWideChars) {
                int stringIndex = RuntimeScalarCache.getOrCreateByteStringIndex(node.value);

                if (stringIndex >= 0) {
                    // Use cached RuntimeScalar
                    mv.visitLdcInsn(stringIndex);
                    mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/runtimetypes/RuntimeScalarCache",
                            "getScalarByteString",
                            "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                            false);
                    return;
                } else {
                    // String is too long for cache or null, create new object
                    mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeScalarReadOnly");
                    mv.visitInsn(Opcodes.DUP);
                    emitStringValue(mv, node.value);
                    mv.visitMethodInsn(
                            Opcodes.INVOKESPECIAL,
                            "org/perlonjava/runtime/runtimetypes/RuntimeScalarReadOnly",
                            "<init>",
                            "(Ljava/lang/String;)V",
                            false);

                    // Set the Perl scalar type to BYTE_STRING
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitLdcInsn(RuntimeScalarType.BYTE_STRING);
                    mv.visitFieldInsn(Opcodes.PUTFIELD, "org/perlonjava/runtime/runtimetypes/RuntimeScalarReadOnly", "type", "I");
                    return;
                }
            }
            // Fall through to create UTF-8 string if hasWideChars
        }

        // Use cache for regular strings
        int stringIndex = RuntimeScalarCache.getOrCreateStringIndex(node.value);

        if (stringIndex >= 0) {
            // Use cached RuntimeScalar
            mv.visitLdcInsn(stringIndex);
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalarCache",
                    "getScalarString",
                    "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                    false);
        } else {
            // String is too long for cache or null, create new object
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeScalarReadOnly");
            mv.visitInsn(Opcodes.DUP);
            emitStringValue(mv, node.value);
            mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalarReadOnly",
                    "<init>",
                    "(Ljava/lang/String;)V",
                    false);
        }
    }

    /**
     * Emits a string value, handling large strings by breaking them into chunks.
     */
    private static void emitStringValue(MethodVisitor mv, String value) {
        // Use a conservative limit to avoid constant pool size issues
        final int MAX_STRING_LENGTH = 16000; // Conservative limit
        boolean isLargeString = value != null && value.length() > MAX_STRING_LENGTH;

        if (!isLargeString) {
            mv.visitLdcInsn(value);
            return;
        }

        // For large strings, use StringBuilder
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(value.length());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder",
                "<init>", "(I)V", false);

        // Break string into chunks
        final int CHUNK_SIZE = 10000; // Safe chunk size
        int offset = 0;

        while (offset < value.length()) {
            int end = Math.min(offset + CHUNK_SIZE, value.length());
            String chunk = value.substring(offset, end);

            mv.visitLdcInsn(chunk);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder",
                    "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);

            offset = end;
        }

        // Convert StringBuilder to String
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder",
                "toString", "()Ljava/lang/String;", false);
    }

    /**
     * Emits bytecode for a list literal (e.g., (1, 2, 3)).
     *
     * <p>Lists in Perl have unique context propagation rules: unlike array and hash
     * literals, regular lists propagate their context to their elements. This means
     * a list in void context evaluates its elements in void context.</p>
     *
     * <p>Context handling:</p>
     * <ul>
     *   <li>VOID: Elements are evaluated in VOID context (Perl-specific behavior)</li>
     *   <li>SCALAR: Creates a RuntimeList, then converts to scalar (returns last element)</li>
     *   <li>LIST: Creates and returns a RuntimeList</li>
     * </ul>
     *
     * @param emitterVisitor The visitor context for emitting bytecode
     * @param node           The ListNode representing the list literal in the AST
     */
    public static void emitList(EmitterVisitor emitterVisitor, ListNode node) {
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(ListNode) in context " + emitterVisitor.ctx.contextType);
        MethodVisitor mv = emitterVisitor.ctx.mv;
        int contextType = emitterVisitor.ctx.contextType;

        // In VOID context, propagate VOID to elements
        if (contextType == RuntimeContextType.VOID) {
            for (Node element : node.elements) {
                element.accept(emitterVisitor);
            }
            if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(ListNode) end");
            return;
        }

        // In SCALAR context, optimize by evaluating elements for side effects
        // and only keeping the last element's value
        if (contextType == RuntimeContextType.SCALAR) {
            if (node.elements.isEmpty()) {
                // Empty list in scalar context returns undef
                EmitOperator.emitUndef(emitterVisitor.ctx.mv);
            } else {
                // Evaluate all elements except the last in scalar context for side effects
                for (int i = 0; i < node.elements.size() - 1; i++) {
                    Node element = node.elements.get(i);
                    element.accept(emitterVisitor);
                    // Pop the result since we only need side effects
                    mv.visitInsn(Opcodes.POP);
                }

                // Evaluate and keep the last element
                node.elements.getLast().accept(emitterVisitor);
                // The last element's value remains on stack as the result
            }
            if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(ListNode) end");
            return;
        }

        // LIST context: Create a new RuntimeList instance
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeList");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/runtimetypes/RuntimeList", "<init>", "()V", false);
        // Stack: [RuntimeList]

        JavaClassInfo.SpillRef listRef = emitterVisitor.ctx.javaClassInfo.acquireSpillRefOrAllocate(emitterVisitor.ctx.symbolTable);
        emitterVisitor.ctx.javaClassInfo.storeSpillRef(mv, listRef);
        // Stack: []

        // Populate the list with elements
        for (Node element : node.elements) {
            // Generate code for the element with an empty operand stack so non-local control flow
            // cannot leak extra operands.
            element.accept(emitterVisitor);
            JavaClassInfo.SpillRef elementRef = emitterVisitor.ctx.javaClassInfo.acquireSpillRefOrAllocate(emitterVisitor.ctx.symbolTable);
            emitterVisitor.ctx.javaClassInfo.storeSpillRef(mv, elementRef);

            emitterVisitor.ctx.javaClassInfo.loadSpillRef(mv, listRef);
            emitterVisitor.ctx.javaClassInfo.loadSpillRef(mv, elementRef);
            emitterVisitor.ctx.javaClassInfo.releaseSpillRef(elementRef);

            // Add the element to the list
            addElementToList(mv, element, contextType);
            // Stack: []
        }

        emitterVisitor.ctx.javaClassInfo.loadSpillRef(mv, listRef);
        emitterVisitor.ctx.javaClassInfo.releaseSpillRef(listRef);
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(ListNode) end");
    }

    /**
     * Emits bytecode for a numeric literal.
     *
     * <p>Handles both integer and floating-point numbers, with support for
     * underscores as digit separators (e.g., 1_000_000). The method optimizes
     * integer handling by using a cache for common values.</p>
     *
     * <p>Optimizations:</p>
     * <ul>
     *   <li>VOID context: No code is generated</li>
     *   <li>Cached integers: Uses RuntimeScalarCache for common integer values</li>
     *   <li>Unboxed context: Pushes primitive int or double values</li>
     * </ul>
     *
     * @param ctx  The emission context containing method visitor and context information
     * @param node The NumberNode containing the numeric value as a string
     */
    public static void emitNumber(EmitterContext ctx, NumberNode node) {
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(NumberNode) in context " + ctx.contextType);

        // Skip code generation in void context
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }

        MethodVisitor mv = ctx.mv;
        // Remove underscores which Perl allows as digit separators
        String value = node.value.replace("_", "");
        boolean isInteger = isInteger(value);

        // For 32-bit Perl emulation, check if this is a large integer
        // that needs to be stored as a string to preserve precision
        boolean isLargeInteger = !isInteger && value.matches("^-?\\d+$");
        // This looks like an integer but failed Integer.parseInt
        // It must be too large for 32-bit int

        if (ctx.isBoxed) {
            // Boxed context: create a RuntimeScalar object
            if (isInteger) {
                if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(NumberNode) emit boxed integer");
                // Use cached RuntimeScalar for common integer values
                mv.visitLdcInsn(Integer.valueOf(value));
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/RuntimeScalarCache",
                        "getScalarInt",
                        "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            } else if (isLargeInteger) {
                // Store large integers with precision preservation.
                // Try long first (exact for values up to 2^63-1).
                // RuntimeScalar(long) uses initializeWithLong() which stores values
                // within 2^53 as DOUBLE and larger ones as STRING for full precision.
                // Fall back to double for values that overflow long (e.g., unsigned 64-bit).
                if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(NumberNode) emit large integer");
                boolean fitsInLong = true;
                long longVal = 0;
                try {
                    longVal = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    fitsInLong = false;
                }
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeScalar");
                mv.visitInsn(Opcodes.DUP);
                if (fitsInLong) {
                    mv.visitLdcInsn(longVal);
                    mv.visitMethodInsn(
                            Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                            "<init>", "(J)V", false);
                } else {
                    // Value exceeds long range — store as double (Perl NV promotion)
                    mv.visitLdcInsn(Double.valueOf(value));
                    mv.visitMethodInsn(
                            Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                            "<init>", "(D)V", false);
                }
            } else {
                // Create new RuntimeScalar for floating-point values
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeScalar");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(Double.valueOf(value));
                mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                        "<init>", "(D)V", false);
            }
        } else {
            // Unboxed context: push primitive values
            if (isInteger) {
                mv.visitLdcInsn(Integer.parseInt(value));
            } else if (isLargeInteger) {
                // For unboxed context, convert to double (only option for primitive numeric)
                // This may lose precision for values beyond 2^53
                mv.visitLdcInsn(Double.parseDouble(value));
            } else {
                mv.visitLdcInsn(Double.parseDouble(value));
            }
        }
    }

    /**
     * Emits bytecode for a bareword identifier.
     *
     * <p>Barewords in Perl are unquoted strings that can be used as string literals
     * when strict subs is not enabled. When strict subs is enabled, barewords
     * trigger a compilation error.</p>
     *
     * <p>Behavior:</p>
     * <ul>
     *   <li>VOID context: No code is generated (barewords have no side effects)</li>
     *   <li>Strict subs enabled: Throws compilation error</li>
     *   <li>Otherwise: Treats the bareword as a string literal</li>
     * </ul>
     *
     * @param visitor The emitter visitor for context
     * @param ctx     The emission context
     * @param node    The IdentifierNode containing the bareword
     * @throws PerlCompilerException if strict subs is enabled
     */
    public static void emitIdentifier(EmitterVisitor visitor, EmitterContext ctx, IdentifierNode node) {
        // Barewords have no side effects in void context
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }

        // Barewords ending with :: are package name constants, always allowed under strict subs
        // e.g., Tie::RefHash:: is equivalent to "Tie::RefHash"
        if (node.name.endsWith("::")) {
            String packageName = node.name.substring(0, node.name.length() - 2);
            new StringNode(packageName, node.tokenIndex).accept(visitor);
            return;
        }

        if (ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_SUBS)) {
            throw new PerlCompilerException(
                    node.tokenIndex,
                    "Bareword \"" + node.name + "\" not allowed while \"strict subs\" in use",
                    ctx.errorUtil);
        } else {
            // Treat bareword as a string literal
            new StringNode(node.name, node.tokenIndex).accept(visitor);
        }
    }

    /**
     * Adds an element to a RuntimeList with type-specific optimizations.
     *
     * <p>This method uses compile-time type information to generate optimized
     * bytecode that calls the specific addToList method for known types, avoiding
     * the overhead of interface dispatch when possible.</p>
     *
     * <p>Stack transformation: [RuntimeList] [element] → [RuntimeList]</p>
     *
     * @param mv          The method visitor for bytecode generation
     * @param element     The AST node representing the element being added
     * @param contextType The context type (used for scalar context optimization)
     */
    private static void addElementToList(MethodVisitor mv, Node element, int contextType) {
        String returnType;
        // Stack: [RuntimeList] [element]

        // Determine the element's return type for optimization
        if (contextType == RuntimeContextType.SCALAR) {
            // In scalar context, all elements are treated as scalars
            returnType = RuntimeDescriptorConstants.SCALAR_TYPE;
        } else if (contextType == RuntimeContextType.RUNTIME) {
            // In RUNTIME context, array/hash elements may have been converted to RuntimeBase
            // via emitRuntimeContextConversion(), so we must use the generic add(RuntimeBase)
            returnType = RuntimeDescriptorConstants.BASE_TYPE;
        } else {
            // Use static analysis to determine the element's return type
            returnType = ReturnTypeVisitor.getReturnType(element);
        }

        // Generate type-specific method call for better performance
        if (RuntimeDescriptorConstants.isKnownRuntimeType(returnType)) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RuntimeDescriptorConstants.LIST_CLASS,
                    "add", "(" + returnType + ")V", false);
        } else {
            // Fall back for unknown types
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RuntimeDescriptorConstants.LIST_CLASS,
                    "add", "(" + RuntimeDescriptorConstants.BASE_TYPE + ")V", false);
        }
    }

    /**
     * Adds an element to a RuntimeArray with type-specific optimizations.
     *
     * <p>Similar to {@link #addElementToList}, this method uses compile-time type
     * information to generate optimized bytecode for known types, improving performance
     * by avoiding interface dispatch when possible.</p>
     *
     * <p>Stack transformation: [RuntimeArray] [element] → [RuntimeArray]</p>
     *
     * @param mv      The method visitor for bytecode generation
     * @param element The AST node representing the element being added
     */
    private static void addElementToArray(MethodVisitor mv, Node element) {
        // Use static analysis to determine the element's return type
        String returnType = ReturnTypeVisitor.getReturnType(element);

        // Generate type-specific method call for better performance
        if (RuntimeDescriptorConstants.isKnownRuntimeType(returnType)) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RuntimeDescriptorConstants.ARRAY_CLASS,
                    "add", "(" + returnType + ")V", false);
        } else {
            // Fall back for unknown types
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RuntimeDescriptorConstants.ARRAY_CLASS,
                    "add", "(" + RuntimeDescriptorConstants.BASE_TYPE + ")V", false);
        }
    }
}
