package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.astvisitor.ReturnTypeVisitor;
import org.perlonjava.runtime.*;

import static org.perlonjava.perlmodule.Strict.STRICT_SUBS;
import static org.perlonjava.runtime.ScalarUtils.isInteger;

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
        emitterVisitor.ctx.logDebug("visit(ArrayLiteralNode) in context " + emitterVisitor.ctx.contextType);
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
            emitterVisitor.ctx.logDebug("visit(ArrayLiteralNode) end");
            return;
        }

        // Create a new RuntimeArray instance
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeArray");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeArray", "<init>", "()V", false);
        // Stack: [RuntimeArray]

        // Populate the array with elements
        for (Node element : node.elements) {
            // Duplicate the RuntimeArray reference for the add operation
            mv.visitInsn(Opcodes.DUP);
            // Stack: [RuntimeArray] [RuntimeArray]

            emitterVisitor.ctx.javaClassInfo.incrementStackLevel(2);

            // Generate code for the element in LIST context
            element.accept(elementContext);
            // Stack: [RuntimeArray] [RuntimeArray] [element]

            emitterVisitor.ctx.javaClassInfo.decrementStackLevel(2);

            // Add the element to the array
            addElementToArray(mv, element);
            // Stack: [RuntimeArray]
        }

        // Convert the array to a reference (array literals produce references)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider",
                "createReference", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);

        emitterVisitor.ctx.logDebug("visit(ArrayLiteralNode) end");
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
        emitterVisitor.ctx.logDebug("visit(HashLiteralNode) in context " + emitterVisitor.ctx.contextType);
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Optimization: In VOID context, evaluate elements for side effects only
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            EmitterVisitor elementContext = emitterVisitor.with(RuntimeContextType.LIST);
            for (Node element : node.elements) {
                element.accept(elementContext);
                // Pop the list result since we don't need it
                mv.visitInsn(Opcodes.POP);
            }
            emitterVisitor.ctx.logDebug("visit(HashLiteralNode) end");
            return;
        }

        // Create a RuntimeList from the hash elements
        // This delegates to emitList which handles the LIST context properly
        ListNode listNode = new ListNode(node.elements, node.tokenIndex);
        listNode.accept(emitterVisitor.with(RuntimeContextType.LIST));

        // Convert the list to a hash reference
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeHash",
                "createHashRef",
                "(Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

        emitterVisitor.ctx.logDebug("visit(HashLiteralNode) end");
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

        if (ctx.isBoxed) {
            if (node.isVString) {
                // V-strings: no caching (they are rare)
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeScalar");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(node.value);
                mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        "org/perlonjava/runtime/RuntimeScalar",
                        "<init>",
                        "(Ljava/lang/String;)V",
                        false);

                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(RuntimeScalarType.VSTRING);
                mv.visitFieldInsn(Opcodes.PUTFIELD, "org/perlonjava/runtime/RuntimeScalar", "type", "I");
            } else {
                // Use cache for regular strings
                int stringIndex = RuntimeScalarCache.getOrCreateStringIndex(node.value);

                if (stringIndex >= 0) {
                    // Use cached RuntimeScalar
                    mv.visitLdcInsn(stringIndex);
                    mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/RuntimeScalarCache",
                            "getScalarString",
                            "(I)Lorg/perlonjava/runtime/RuntimeScalar;",
                            false);
                } else {
                    // String is too long or null, create new object
                    mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeScalar");
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitLdcInsn(node.value);
                    mv.visitMethodInsn(
                            Opcodes.INVOKESPECIAL,
                            "org/perlonjava/runtime/RuntimeScalar",
                            "<init>",
                            "(Ljava/lang/String;)V",
                            false);
                }
            }
        } else {
            // Unboxed context: just push the string value
            mv.visitLdcInsn(node.value);
        }
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
        emitterVisitor.ctx.logDebug("visit(ListNode) in context " + emitterVisitor.ctx.contextType);
        MethodVisitor mv = emitterVisitor.ctx.mv;
        int contextType = emitterVisitor.ctx.contextType;

        // In VOID context, propagate VOID to elements
        if (contextType == RuntimeContextType.VOID) {
            for (Node element : node.elements) {
                element.accept(emitterVisitor);
            }
            emitterVisitor.ctx.logDebug("visit(ListNode) end");
            return;
        }

        // In SCALAR context, optimize by evaluating elements for side effects
        // and only keeping the last element's value
        if (contextType == RuntimeContextType.SCALAR) {
            if (node.elements.isEmpty()) {
                // Empty list in scalar context returns undef
                mv.visitFieldInsn(Opcodes.GETSTATIC, "org/perlonjava/runtime/RuntimeScalar",
                        "undef", "Lorg/perlonjava/runtime/RuntimeScalar;");
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
            emitterVisitor.ctx.logDebug("visit(ListNode) end");
            return;
        }

        // LIST context: Create a new RuntimeList instance
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeList");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeList", "<init>", "()V", false);
        // Stack: [RuntimeList]

        // Populate the list with elements
        for (Node element : node.elements) {
            // Duplicate the RuntimeList reference for the add operation
            mv.visitInsn(Opcodes.DUP);
            // Stack: [RuntimeList] [RuntimeList]

            emitterVisitor.ctx.javaClassInfo.incrementStackLevel(2);

            // Generate code for the element, preserving the list's context
            element.accept(emitterVisitor);
            // Stack: [RuntimeList] [RuntimeList] [element]

            emitterVisitor.ctx.javaClassInfo.decrementStackLevel(2);

            // Add the element to the list
            addElementToList(mv, element, contextType);
            // Stack: [RuntimeList]
        }
        emitterVisitor.ctx.logDebug("visit(ListNode) end");
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
        ctx.logDebug("visit(NumberNode) in context " + ctx.contextType);

        // Skip code generation in void context
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }

        MethodVisitor mv = ctx.mv;
        // Remove underscores which Perl allows as digit separators
        String value = node.value.replace("_", "");
        boolean isInteger = isInteger(value);

        if (ctx.isBoxed) {
            // Boxed context: create a RuntimeScalar object
            if (isInteger) {
                ctx.logDebug("visit(NumberNode) emit boxed integer");
                // Use cached RuntimeScalar for common integer values
                mv.visitLdcInsn(Integer.valueOf(value));
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/RuntimeScalarCache",
                        "getScalarInt",
                        "(I)Lorg/perlonjava/runtime/RuntimeScalar;", false);
            } else {
                // Create new RuntimeScalar for floating-point values
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeScalar");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(Double.valueOf(value));
                mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeScalar",
                        "<init>", "(D)V", false);
            }
        } else {
            // Unboxed context: push primitive values
            if (isInteger) {
                mv.visitLdcInsn(Integer.parseInt(value));
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

        if (ctx.symbolTable.isStrictOptionEnabled(STRICT_SUBS)) {
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

        // Swap stack to prepare for method call: [element] [RuntimeList]
        mv.visitInsn(Opcodes.SWAP);

        // Determine the element's return type for optimization
        if (contextType == RuntimeContextType.SCALAR) {
            // In scalar context, all elements are treated as scalars
            returnType = RuntimeTypeConstants.SCALAR_TYPE;
        } else {
            // Use static analysis to determine the element's return type
            returnType = ReturnTypeVisitor.getReturnType(element);
        }

        // Generate type-specific method call for better performance
        if (RuntimeTypeConstants.isKnownRuntimeType(returnType)) {
            // Extract the internal class name from the type descriptor
            String className = RuntimeTypeConstants.descriptorToInternalName(returnType);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className,
                    "addToList", "(" + RuntimeTypeConstants.LIST_TYPE + ")V", false);
        } else {
            // Fall back to interface call for unknown types
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RuntimeTypeConstants.BASE_CLASS,
                    "addToList", "(" + RuntimeTypeConstants.LIST_TYPE + ")V", false);
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

        // Swap stack to prepare for method call: [element] [RuntimeArray]
        mv.visitInsn(Opcodes.SWAP);

        // Generate type-specific method call for better performance
        if (RuntimeTypeConstants.isKnownRuntimeType(returnType)) {
            // Extract the internal class name from the type descriptor
            String className = RuntimeTypeConstants.descriptorToInternalName(returnType);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className,
                    "addToArray", "(" + RuntimeTypeConstants.ARRAY_TYPE + ")V", false);
        } else {
            // Fall back to interface call for unknown types
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RuntimeTypeConstants.BASE_CLASS,
                    "addToArray", "(" + RuntimeTypeConstants.ARRAY_TYPE + ")V", false);
        }
    }
}
