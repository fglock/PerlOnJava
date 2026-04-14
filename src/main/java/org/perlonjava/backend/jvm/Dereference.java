package org.perlonjava.backend.jvm;

import org.perlonjava.app.cli.CompilerOptions;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.perlmodule.Strict;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import static org.perlonjava.backend.jvm.EmitSubroutine.handleSelfCallOperator;
import static org.perlonjava.runtime.perlmodule.Strict.HINT_STRICT_REFS;

public class Dereference {
    // Callsite ID counter for inline method caching (unique across all compilations)
    private static int nextMethodCallsiteId = 0;
    
    /**
     * Handles the postfix `[]` operator.
     */
    static void handleArrayElementOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node, String arrayOperation) {
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("handleArrayElementOperator " + node + " in context " + emitterVisitor.ctx.contextType);
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        // check if node.left is a `$` or `@` variable - it means we have a RuntimeArray instead of RuntimeScalar
        if (node.left instanceof OperatorNode sigilNode) { // $ @ %
            String sigil = sigilNode.operator;
            if (sigil.equals("$") && sigilNode.operand instanceof IdentifierNode identifierNode) {
                /*  $a[10]
                 *  BinaryOperatorNode: [
                 *    OperatorNode: $
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      NumberNode: 10
                 */
                // Rewrite the variable node from `$` to `@`
                OperatorNode varNode = new OperatorNode("@", identifierNode, sigilNode.tokenIndex);

                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) $var[] ");
                varNode.accept(emitterVisitor.with(RuntimeContextType.LIST)); // target - left parameter

                int arraySlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledArray = arraySlot >= 0;
                if (!pooledArray) {
                    arraySlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, arraySlot);

                ArrayLiteralNode right = (ArrayLiteralNode) node.right;
                if (right.elements.size() == 1) {
                    Node elem = right.elements.getFirst();

                    // Special case: numeric literal - use get(int) directly
                    if (elem instanceof NumberNode numberNode && numberNode.value.indexOf('.') == -1) {
                        try {
                            int index = Integer.parseInt(numberNode.value);
                            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, arraySlot);
                            emitterVisitor.ctx.mv.visitLdcInsn(index);
                            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeArray",
                                    arrayOperation, "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                        } catch (NumberFormatException e) {
                            // Fall back to RuntimeScalar if the number is too large
                            elem.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, arraySlot);
                            emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);
                            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeArray",
                                    arrayOperation, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                        }
                    } else {
                        // Single element but not an integer literal
                        elem.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, arraySlot);
                        emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeArray",
                                arrayOperation, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                    }
                } else {
                    // emit the [0] as a RuntimeList
                    ListNode nodeRight = right.asListNode();
                    nodeRight.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, arraySlot);
                    emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeArray",
                            arrayOperation, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                }

                if (pooledArray) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }

                // Only force FETCH for "get" operations - delete/exists can return null
                if (arrayOperation.equals("get")) {
                    EmitOperator.handleVoidContextForTied(emitterVisitor);
                } else {
                    EmitOperator.handleVoidContext(emitterVisitor);
                }
                return;
            }
            if (sigil.equals("$") && sigilNode.operand instanceof BlockNode) {
                /*  ${$ref->[2]}[10]
                 *  BinaryOperatorNode: [
                 *    OperatorNode: $
                 *      BlockNode: $ref->[2]
                 *    ArrayLiteralNode:
                 *      NumberNode: 10
                 *
                 * In Perl, ${EXPR}[index] does NOT call scalarDeref on EXPR.
                 * Instead, it evaluates EXPR and applies the subscript directly.
                 * This allows ${$aref}[0] to work even though ${$aref} alone would fail.
                 */
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) ${BLOCK}[] ");

                // Evaluate the block expression to get a RuntimeScalar (might be array/hash ref)
                sigilNode.operand.accept(scalarVisitor);

                int baseSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledBase = baseSlot >= 0;
                if (!pooledBase) {
                    baseSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, baseSlot);

                // Now apply the subscript using arrayDerefGet method
                ArrayLiteralNode right = (ArrayLiteralNode) node.right;
                if (right.elements.size() == 1) {
                    Node elem = right.elements.getFirst();
                    elem.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, baseSlot);
                    emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);
                    // Check strict refs at compile time
                    if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_STRICT_REFS)) {
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                "arrayDerefGet", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                    } else {
                        // Push current package for non-strict symbolic reference resolution
                        emitterVisitor.pushCurrentPackage();
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                "arrayDerefGetNonStrict", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                    }

                    if (pooledBase) {
                        emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                    }

                    // Force FETCH for tied variables in void context (single-element access only)
                    EmitOperator.handleVoidContextForTied(emitterVisitor);
                    return;
                } else {
                    // Multiple indices - use slice
                    ListNode nodeRight = right.asListNode();
                    nodeRight.accept(emitterVisitor.with(RuntimeContextType.LIST));
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, baseSlot);
                    emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                            "arrayDerefGetSlice", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;", false);

                    // Handle context conversion
                    if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeList",
                                "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                    } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                        emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
                    }

                    if (pooledBase) {
                        emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                    }

                    return;
                }
            }
            if (sigil.equals("@")) {
                /*  @a[10, 20]
                 *  BinaryOperatorNode: [
                 *    OperatorNode: @
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      NumberNode: 10
                 *      NumberNode: 20
                 */
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) @var[] ");
                sigilNode.accept(emitterVisitor.with(RuntimeContextType.LIST)); // target - left parameter

                int arraySlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledArray = arraySlot >= 0;
                if (!pooledArray) {
                    arraySlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, arraySlot);

                // emit the [10, 20] as a RuntimeList
                ListNode nodeRight = ((ArrayLiteralNode) node.right).asListNode();
                nodeRight.accept(emitterVisitor.with(RuntimeContextType.LIST));

                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, arraySlot);
                emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);

                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeArray",
                        arrayOperation + "Slice", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;", false);

                if (pooledArray) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }

                // Handle context conversion for array slices
                if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                    // Convert RuntimeList to RuntimeScalar (Perl scalar slice semantics = last element or undef)
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeList",
                            "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }

            if (sigil.equals("%") && sigilNode.operand instanceof IdentifierNode
                    && (arrayOperation.equals("get") || arrayOperation.equals("delete"))) {
                /*  %a[10, 20] - get/delete key-value slice of array
                 *  BinaryOperatorNode: [
                 *    OperatorNode: %
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      NumberNode: 10
                 *      NumberNode: 20
                 */
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) %array[] ");

                // Rewrite variable from % to @ to get the array
                OperatorNode varNode = new OperatorNode("@", sigilNode.operand, sigilNode.tokenIndex);
                varNode.accept(emitterVisitor.with(RuntimeContextType.LIST)); // target - left parameter

                int arraySlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledArray = arraySlot >= 0;
                if (!pooledArray) {
                    arraySlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, arraySlot);

                // emit the [10, 20] as a RuntimeList
                ListNode nodeRight = ((ArrayLiteralNode) node.right).asListNode();
                nodeRight.accept(emitterVisitor.with(RuntimeContextType.LIST));

                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, arraySlot);
                emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);

                // Call the appropriate method based on operation
                String methodName = arrayOperation.equals("delete") ? "deleteKeyValueSlice" : "getKeyValueSlice";
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeArray",
                        methodName, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;", false);

                if (pooledArray) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }

                // Handle context conversion for array kv-slices
                if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeList",
                            "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }

            if (sigil.equals("%") && arrayOperation.equals("get")) {
                /*  $aref->%[1, 7, 3]
                 *  BinaryOperatorNode: [
                 *    OperatorNode: %
                 *      OperatorNode: $
                 *        IdentifierNode: aref
                 *    ArrayLiteralNode:
                 *      NumberNode: 1
                 *      NumberNode: 7
                 *      NumberNode: 3
                 *
                 * Perl index/value slice: returns alternating index and value.
                 */

                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) %var[] ");

                // Evaluate base as scalar (array reference)
                sigilNode.operand.accept(scalarVisitor);

                int baseSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledBase = baseSlot >= 0;
                if (!pooledBase) {
                    baseSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, baseSlot);

                // Build list of alternating index/value pairs
                emitterVisitor.ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeList");
                emitterVisitor.ctx.mv.visitInsn(Opcodes.DUP);
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeList",
                        "<init>", "()V", false);

                int outSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledOut = outSlot >= 0;
                if (!pooledOut) {
                    outSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, outSlot);

                ArrayLiteralNode right = (ArrayLiteralNode) node.right;
                int idxSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledIdx = idxSlot >= 0;
                if (!pooledIdx) {
                    idxSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }

                for (Node elem : right.elements) {
                    // Evaluate index scalar
                    elem.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, idxSlot);

                    // out.add(index)
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, outSlot);
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, idxSlot);
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            "org/perlonjava/runtime/runtimetypes/RuntimeList",
                            "add", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V", false);

                    // out.add(base.arrayDerefGet(index))
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, outSlot);
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, baseSlot);
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, idxSlot);
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                            "arrayDerefGet",
                            "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                            false);
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            "org/perlonjava/runtime/runtimetypes/RuntimeList",
                            "add", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V", false);
                }

                if (pooledIdx) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }

                // Load result
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, outSlot);

                if (pooledOut) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }
                if (pooledBase) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }

                // Context conversion
                if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            "org/perlonjava/runtime/runtimetypes/RuntimeList",
                            "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }
        }
        if (node.left instanceof ListNode list) { // ("a","b","c")[2]
            // Use proper list slice semantics: evaluate list, then slice
            // This differs from array dereference because empty list returns empty, not undef
            if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) (list)[indices] - list slice");
            
            // Evaluate the list
            list.accept(emitterVisitor.with(RuntimeContextType.LIST));
            
            // Convert to RuntimeList if not already (handles RuntimeScalar case)
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                    "getList",
                    "()Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                    false);
            
            // Save the list to a local variable before evaluating indices.
            // This is necessary because indices may contain function calls that
            // generate complex bytecode with exception handlers, and the JVM
            // verifier requires consistent stack heights at merge points.
            int listVar = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, listVar);
            
            // Evaluate the indices
            ListNode indices = ((ArrayLiteralNode) node.right).asListNode();
            indices.accept(emitterVisitor.with(RuntimeContextType.LIST));
            
            // Save indices to local variable too
            int indicesVar = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, indicesVar);
            
            // Load list and indices back, call RuntimeList.getSlice(indices)
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, listVar);
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, indicesVar);
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeList",
                    "getSlice",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                    false);
            
            // Handle context conversion
            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeList",
                        "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
            }
            return;
        }

        // For function calls and other expressions: (func())[index]
        // We need to use list slice semantics to handle empty lists correctly.
        // However, this should NOT apply to chained dereferences like $matrix[1][0]
        // where the first [1] returns a scalar (array reference) and the second
        // [0] should dereference it.
        // 
        // List slice semantics apply when:
        // 1. The left side is a ListNode (literal list) - handled above
        // 2. The left side is a parenthesized function call (wantarray context)
        //
        // For now, we use the old transformation to ->[] for non-ListNode cases,
        // as most cases are array dereferences, not list slices.
        // TODO: Properly detect when the left side is a list-returning expression
        //       vs. a scalar-returning expression.

        // default: call `->[]`
        BinaryOperatorNode refNode = new BinaryOperatorNode("->", node.left, node.right, node.tokenIndex);
        refNode.accept(emitterVisitor);
    }

    /**
     * Handles the postfix `{}` node.
     * <p>
     * hashOperation is one of: "get", "delete", "exists"
     */
    public static void handleHashElementOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node, String hashOperation) {
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("handleHashElementOperator " + node + " in context " + emitterVisitor.ctx.contextType);
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        // check if node.left is a `$` or `@` variable
        if (node.left instanceof OperatorNode sigilNode) { // $ @ %
            String sigil = sigilNode.operator;
            if (sigil.equals("$") && sigilNode.operand instanceof IdentifierNode identifierNode) {
                /*  $a{"a"}
                 *  BinaryOperatorNode: {
                 *    OperatorNode: $
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      StringNode: a
                 */

                // Rewrite the variable node from `$` to `%`
                OperatorNode varNode = new OperatorNode("%", identifierNode, sigilNode.tokenIndex);

                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) $var{} ");
                varNode.accept(emitterVisitor.with(RuntimeContextType.LIST)); // target - left parameter

                int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledLeft = leftSlot >= 0;
                if (!pooledLeft) {
                    leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

                // emit the {x} as a RuntimeList
                ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();

                Node nodeZero = nodeRight.elements.getFirst();
                if (nodeRight.elements.size() == 1 && nodeZero instanceof IdentifierNode) {
                    // Convert IdentifierNode to StringNode:  {a} to {"a"}
                    nodeRight.elements.set(0, new StringNode(((IdentifierNode) nodeZero).name, ((IdentifierNode) nodeZero).tokenIndex));
                    nodeZero = nodeRight.elements.getFirst(); // Update nodeZero to the new StringNode
                }

                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) $var{}  autoquote " + node.right);

                // Optimization: if there's only one element and it's a string literal
                if (nodeRight.elements.size() == 1 && nodeZero instanceof StringNode) {
                    // Special case: string literal - use get(String) directly
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
                    emitterVisitor.ctx.mv.visitLdcInsn(((StringNode) nodeZero).value);
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeHash",
                            hashOperation, "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                } else if (nodeRight.elements.size() == 1) {
                    // Single element but not a string literal
                    Node elem = nodeRight.elements.getFirst();
                    elem.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

                    int keySlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                    boolean pooledKey = keySlot >= 0;
                    if (!pooledKey) {
                        keySlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                    }
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, keySlot);

                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, keySlot);
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeHash",
                            hashOperation, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);

                    if (pooledKey) {
                        emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                    }
                } else {
                    // Multiple elements: join them with $; (SUBSEP)
                    // Get the $; global variable (SUBSEP)
                    emitterVisitor.ctx.mv.visitLdcInsn("main::;");
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/runtimetypes/GlobalVariable",
                            "getGlobalVariable", "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);

                    int sepSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                    boolean pooledSep = sepSlot >= 0;
                    if (!pooledSep) {
                        sepSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                    }
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, sepSlot);

                    // Emit the list of elements
                    nodeRight.accept(emitterVisitor.with(RuntimeContextType.LIST));
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, sepSlot);
                    emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);
                    // Call join(separator, list)
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/operators/StringOperators",
                            "join", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                    // Use the joined string as the hash key
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
                    emitterVisitor.ctx.mv.visitInsn(Opcodes.SWAP);
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeHash",
                            hashOperation, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);

                    if (pooledSep) {
                        emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                    }
                }

                if (pooledLeft) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }

                // Only force FETCH for "get" operations - delete/exists can return null
                if (hashOperation.equals("get")) {
                    EmitOperator.handleVoidContextForTied(emitterVisitor);
                } else {
                    EmitOperator.handleVoidContext(emitterVisitor);
                }
                return;
            }
            if (sigil.equals("$") && sigilNode.operand instanceof BlockNode) {
                /*  ${$ref->{key}}{"key2"}
                 *  BinaryOperatorNode: {
                 *    OperatorNode: $
                 *      BlockNode: $ref->{key}
                 *    HashLiteralNode:
                 *      StringNode: "key2"
                 *
                 * In Perl, ${EXPR}{key} does NOT call scalarDeref on EXPR.
                 * Instead, it evaluates EXPR and applies the subscript directly.
                 * This allows ${$href}{key} to work even though ${$href} alone would fail.
                 */
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) ${BLOCK}{} ");

                // Evaluate the block expression to get a RuntimeScalar (might be array/hash ref)
                sigilNode.operand.accept(scalarVisitor);

                // Now apply the subscript using hashDerefGet method
                ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();

                Node nodeZero = nodeRight.elements.getFirst();
                if (nodeRight.elements.size() == 1 && nodeZero instanceof IdentifierNode) {
                    // Convert IdentifierNode to StringNode:  {a} to {"a"}
                    nodeRight.elements.set(0, new StringNode(((IdentifierNode) nodeZero).name, ((IdentifierNode) nodeZero).tokenIndex));
                    nodeZero = nodeRight.elements.getFirst();
                }

                // Apply hash subscript
                if (nodeRight.elements.size() == 1) {
                    // Single element
                    Node elem = nodeRight.elements.getFirst();
                    elem.accept(scalarVisitor);
                    if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_REFS)) {
                        // Use strict version (throws error on symbolic references)
                        String methodName = switch (hashOperation) {
                            case "get" -> "hashDerefGet";
                            case "delete" -> "hashDerefDelete";
                            case "exists" -> "hashDerefExists";
                            default ->
                                    throw new PerlCompilerException(node.tokenIndex, "Not implemented: hash operation: " + hashOperation, emitterVisitor.ctx.errorUtil);
                        };
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                methodName, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                    } else {
                        // Use non-strict version (allows symbolic references)
                        String methodName = switch (hashOperation) {
                            case "get" -> "hashDerefGetNonStrict";
                            case "delete" -> "hashDerefDeleteNonStrict";
                            case "exists" -> "hashDerefExistsNonStrict";
                            default ->
                                    throw new PerlCompilerException(node.tokenIndex, "Not implemented: hash operation: " + hashOperation, emitterVisitor.ctx.errorUtil);
                        };
                        emitterVisitor.pushCurrentPackage();
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                methodName, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                    }
                } else {
                    // Multiple elements - this is a hash slice, but that's not commonly used with ${}
                    // For now, handle it like the regular case by joining with SUBSEP
                    emitterVisitor.ctx.mv.visitLdcInsn("main::;");
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/runtimetypes/GlobalVariable",
                            "getGlobalVariable", "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                    nodeRight.accept(emitterVisitor.with(RuntimeContextType.LIST));
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/operators/StringOperators",
                            "join", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                    if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_REFS)) {
                        // Use strict version (throws error on symbolic references)
                        String methodName = switch (hashOperation) {
                            case "get" -> "hashDerefGet";
                            case "delete" -> "hashDerefDelete";
                            case "exists" -> "hashDerefExists";
                            default ->
                                    throw new PerlCompilerException(node.tokenIndex, "Not implemented: hash operation: " + hashOperation, emitterVisitor.ctx.errorUtil);
                        };
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                methodName, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                    } else {
                        // Use non-strict version (allows symbolic references)
                        String methodName = switch (hashOperation) {
                            case "get" -> "hashDerefGetNonStrict";
                            case "delete" -> "hashDerefDeleteNonStrict";
                            case "exists" -> "hashDerefExistsNonStrict";
                            default ->
                                    throw new PerlCompilerException(node.tokenIndex, "Not implemented: hash operation: " + hashOperation, emitterVisitor.ctx.errorUtil);
                        };
                        emitterVisitor.pushCurrentPackage();
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                methodName, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                    }
                }

                EmitOperator.handleVoidContextForTied(emitterVisitor);
                return;
            }
            if (sigil.equals("@")) {
                /*  @a{"a", "b"}
                 *  BinaryOperatorNode: {
                 *    OperatorNode: @
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      StringNode: a
                 *      StringNode: b
                 */
                // Rewrite the variable node from `@` to `%`
                OperatorNode varNode = new OperatorNode("%", sigilNode.operand, sigilNode.tokenIndex);

                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) @var{} " + varNode);
                varNode.accept(emitterVisitor.with(RuntimeContextType.LIST)); // target - left parameter

                int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledLeft = leftSlot >= 0;
                if (!pooledLeft) {
                    leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

                // emit the {x} as a RuntimeList
                ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) @var{} as listNode: " + nodeRight);

                if (!nodeRight.elements.isEmpty()) {
                    Node nodeZero = nodeRight.elements.getFirst();
                    if (nodeRight.elements.size() == 1 && nodeZero instanceof IdentifierNode) {
                        // Convert IdentifierNode to StringNode:  {a} to {"a"}
                        nodeRight.elements.set(0, new StringNode(((IdentifierNode) nodeZero).name, ((IdentifierNode) nodeZero).tokenIndex));
                    }
                }

                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) $var{}  autoquote " + node.right);
                nodeRight.accept(emitterVisitor.with(RuntimeContextType.LIST));

                int keyListSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledKeyList = keyListSlot >= 0;
                if (!pooledKeyList) {
                    keyListSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, keyListSlot);

                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, keyListSlot);

                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeHash",
                        hashOperation + "Slice", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;", false);

                if (pooledKeyList) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }

                if (pooledLeft) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }

                // Handle context conversion for hash slices
                if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                    // Convert RuntimeList to RuntimeScalar (Perl scalar slice semantics)
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeList",
                            "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }
            if (sigil.equals("%") && (hashOperation.equals("get") || hashOperation.equals("delete"))) {
                /*  %a{"a", "b"} - get/delete key value slice
                 *  BinaryOperatorNode: {
                 *    OperatorNode: %
                 *      IdentifierNode: a
                 *    ArrayLiteralNode:
                 *      StringNode: a
                 *      StringNode: b
                 */
                // Rewrite the variable node from `%` to `%`
                OperatorNode varNode = new OperatorNode("%", sigilNode.operand, sigilNode.tokenIndex);

                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) %var{} " + varNode);
                varNode.accept(emitterVisitor.with(RuntimeContextType.LIST)); // target - left parameter

                int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledLeft = leftSlot >= 0;
                if (!pooledLeft) {
                    leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

                // emit the {x} as a RuntimeList
                ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) %var{} as listNode: " + nodeRight);

                if (!nodeRight.elements.isEmpty()) {
                    Node nodeZero = nodeRight.elements.getFirst();
                    if (nodeRight.elements.size() == 1 && nodeZero instanceof IdentifierNode) {
                        // Convert IdentifierNode to StringNode:  {a} to {"a"}
                        nodeRight.elements.set(0, new StringNode(((IdentifierNode) nodeZero).name, ((IdentifierNode) nodeZero).tokenIndex));
                    }
                }

                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) %var{}  autoquote " + node.right);
                nodeRight.accept(emitterVisitor.with(RuntimeContextType.LIST));

                int keyListSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledKeyList = keyListSlot >= 0;
                if (!pooledKeyList) {
                    keyListSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, keyListSlot);

                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
                emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, keyListSlot);

                // Call the appropriate method based on operation
                String methodName = hashOperation.equals("delete") ? "deleteKeyValueSlice" : "getKeyValueSlice";
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeHash",
                        methodName, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;", false);

                if (pooledKeyList) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }

                if (pooledLeft) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }

                // Handle context conversion for key/value slice
                if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeList",
                            "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }
            if (sigil.equals("*")) {
                /*  *expr{SLOT} - glob slot access (e.g., *{$self}{HASH}, *foo{CODE})
                 *  BinaryOperatorNode: {
                 *    OperatorNode: *
                 *      <expression>
                 *    HashLiteralNode:
                 *      StringNode: "HASH" (or CODE, SCALAR, ARRAY, IO, etc.)
                 *
                 * This is distinct from *expr->{key} (arrow hash deref on glob),
                 * which goes through handleArrowHashDeref instead.
                 */
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) *expr{SLOT} ");

                // Evaluate the entire *expr to get the RuntimeGlob.
                // This uses the existing * sigil handler in EmitVariable, which properly
                // handles bare names (*FOO), variables (*$var), and blocks (*{expr}).
                sigilNode.accept(scalarVisitor);

                // Evaluate the key (slot name)
                ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();
                Node nodeZero = nodeRight.elements.getFirst();
                if (nodeRight.elements.size() == 1 && nodeZero instanceof IdentifierNode) {
                    nodeRight.elements.set(0, new StringNode(((IdentifierNode) nodeZero).name, ((IdentifierNode) nodeZero).tokenIndex));
                    nodeZero = nodeRight.elements.getFirst();
                }

                if (nodeRight.elements.size() == 1) {
                    nodeZero.accept(scalarVisitor);
                } else {
                    // Multiple keys - join with SUBSEP (unusual for glob slot access but handle it)
                    emitterVisitor.ctx.mv.visitLdcInsn("main::;");
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/runtimetypes/GlobalVariable",
                            "getGlobalVariable", "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                    nodeRight.accept(emitterVisitor.with(RuntimeContextType.LIST));
                    emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/operators/StringOperators",
                            "join", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                }

                // Call getGlobSlot on the RuntimeGlob
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeGlob", "getGlobSlot",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);

                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            }
        }

        // default: call `->{}`
        BinaryOperatorNode refNode = new BinaryOperatorNode("->", node.left, node.right, node.tokenIndex);
        handleArrowHashDeref(emitterVisitor, refNode, hashOperation);
    }

    /**
     * Handles the `->` operator.
     */
    static void handleArrowOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("handleArrowOperator " + node + " in context " + emitterVisitor.ctx.contextType);
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        if (node.right instanceof ListNode) { // ->()

            BinaryOperatorNode applyNode = new BinaryOperatorNode("(", node.left, node.right, node.tokenIndex);
            applyNode.accept(emitterVisitor);

        } else if (node.right instanceof ArrayLiteralNode) { // ->[0]
            handleArrowArrayDeref(emitterVisitor, node, "get");

        } else if (node.right instanceof HashLiteralNode) { // ->{x}
            handleArrowHashDeref(emitterVisitor, node, "get");

        } else {
            // ->method()   ->$method()
            //
            // right is BinaryOperatorNode:"("
            BinaryOperatorNode right = (BinaryOperatorNode) node.right;

            // `object.call(method, arguments, context)`
            Node object = node.left;
            Node method = right.left;
            Node arguments = right.right;

            // Convert class to Stringnode if needed:  Class->method()
            if (object instanceof IdentifierNode) {
                object = new StringNode(((IdentifierNode) object).name, ((IdentifierNode) object).tokenIndex);
            }

            // Convert method to StringNode if needed
            if (method instanceof OperatorNode op) {
                // &method is introduced by the parser if the method is predeclared
                if (op.operator.equals("&")) {
                    method = op.operand;
                }
            }
            if (method instanceof IdentifierNode) {
                method = new StringNode(((IdentifierNode) method).name, ((IdentifierNode) method).tokenIndex);
            }

            object.accept(scalarVisitor);
            method.accept(scalarVisitor);

            // Push __SUB__
            handleSelfCallOperator(emitterVisitor.with(RuntimeContextType.SCALAR), null);

            int subSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledSub = subSlot >= 0;
            if (!pooledSub) {
                subSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            mv.visitVarInsn(Opcodes.ASTORE, subSlot);

            int methodSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledMethod = methodSlot >= 0;
            if (!pooledMethod) {
                methodSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            mv.visitVarInsn(Opcodes.ASTORE, methodSlot);

            int objectSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledObject = objectSlot >= 0;
            if (!pooledObject) {
                objectSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            mv.visitVarInsn(Opcodes.ASTORE, objectSlot);

            // Generate native RuntimeBase[] array for parameters instead of RuntimeList
            ListNode paramList = ListNode.makeList(arguments);
            int argCount = paramList.elements.size();

            int argsArraySlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledArgsArray = argsArraySlot >= 0;
            if (!pooledArgsArray) {
                argsArraySlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }

            // Create array of RuntimeBase with size equal to number of arguments
            if (argCount <= 5) {
                mv.visitInsn(Opcodes.ICONST_0 + argCount);
            } else if (argCount <= 127) {
                mv.visitIntInsn(Opcodes.BIPUSH, argCount);
            } else {
                mv.visitIntInsn(Opcodes.SIPUSH, argCount);
            }
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "org/perlonjava/runtime/runtimetypes/RuntimeBase");

            mv.visitVarInsn(Opcodes.ASTORE, argsArraySlot);

            // Populate the array with arguments
            EmitterVisitor listVisitor = emitterVisitor.with(RuntimeContextType.LIST);
            for (int index = 0; index < argCount; index++) {
                int argSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledArg = argSlot >= 0;
                if (!pooledArg) {
                    argSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                }

                paramList.elements.get(index).accept(listVisitor);
                mv.visitVarInsn(Opcodes.ASTORE, argSlot);

                mv.visitVarInsn(Opcodes.ALOAD, argsArraySlot);
                if (index <= 5) {
                    mv.visitInsn(Opcodes.ICONST_0 + index);
                } else if (index <= 127) {
                    mv.visitIntInsn(Opcodes.BIPUSH, index);
                } else {
                    mv.visitIntInsn(Opcodes.SIPUSH, index);
                }
                mv.visitVarInsn(Opcodes.ALOAD, argSlot);
                mv.visitInsn(Opcodes.AASTORE);

                if (pooledArg) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                }
            }

            // Save the call context into a local slot for the TAILCALL trampoline.
            int callContextSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            emitterVisitor.pushCallContext();
            mv.visitVarInsn(Opcodes.ISTORE, callContextSlot);

            // Allocate a unique callsite ID for inline method caching
            int callsiteId = nextMethodCallsiteId++;
            mv.visitLdcInsn(callsiteId);
            mv.visitVarInsn(Opcodes.ALOAD, objectSlot);
            mv.visitVarInsn(Opcodes.ALOAD, methodSlot);
            mv.visitVarInsn(Opcodes.ALOAD, subSlot);
            mv.visitVarInsn(Opcodes.ALOAD, argsArraySlot);
            mv.visitVarInsn(Opcodes.ILOAD, callContextSlot);   // push saved call context
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                    "callCached",
                    "(ILorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                    false); // generate a cached .call()

            // Tagged returns control-flow handling for method calls:
            // If RuntimeCode.callCached() returned a RuntimeControlFlowList marker (TAILCALL), handle it here.
            if (emitterVisitor.ctx.javaClassInfo.returnLabel != null
                    && emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot >= 0) {

                // Get or create a block-level dispatcher for the current loop state
                String loopStateSignature = emitterVisitor.ctx.javaClassInfo.getLoopStateSignature();
                Label blockDispatcher = emitterVisitor.ctx.javaClassInfo.blockDispatcherLabels.get(loopStateSignature);
                boolean isFirstUse = (blockDispatcher == null);

                if (isFirstUse) {
                    blockDispatcher = new Label();
                    emitterVisitor.ctx.javaClassInfo.blockDispatcherLabels.put(loopStateSignature, blockDispatcher);
                }

                Label notControlFlow = new Label();

                // Store result in temp slot
                mv.visitVarInsn(Opcodes.ASTORE, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);

                // Load and check if it's a control flow marker
                mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeList",
                        "isNonLocalGoto",
                        "()Z",
                        false);
                mv.visitJumpInsn(Opcodes.IFEQ, notControlFlow);

                // Marked: check if TAILCALL (handle locally with trampoline)
                Label tailcallLoop = new Label();
                Label notTailcall = new Label();

                // Check if type is TAILCALL
                mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                        "getControlFlowType",
                        "()Lorg/perlonjava/runtime/runtimetypes/ControlFlowType;",
                        false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/ControlFlowType",
                        "ordinal",
                        "()I",
                        false);
                mv.visitInsn(Opcodes.ICONST_4);  // TAILCALL.ordinal() = 4
                mv.visitJumpInsn(Opcodes.IF_ICMPNE, notTailcall);

                // TAILCALL trampoline loop - handle tail calls at the call site
                mv.visitLabel(tailcallLoop);

                // Extract codeRef and args from the marker
                mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                        "getTailCallCodeRef",
                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                        false);
                mv.visitVarInsn(Opcodes.ASTORE, emitterVisitor.ctx.javaClassInfo.tailCallCodeRefSlot);

                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                        "getTailCallArgs",
                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;",
                        false);
                mv.visitVarInsn(Opcodes.ASTORE, emitterVisitor.ctx.javaClassInfo.tailCallArgsSlot);

                // Call target: RuntimeCode.apply(codeRef, "tailcall", args, context)
                mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.tailCallCodeRefSlot);
                mv.visitLdcInsn("tailcall");
                mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.tailCallArgsSlot);
                mv.visitVarInsn(Opcodes.ILOAD, callContextSlot);  // context of the original call site
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                        "apply",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                        false);

                // Store result to controlFlowTempSlot
                mv.visitVarInsn(Opcodes.ASTORE, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);

                // Check if result is still a control flow marker
                mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeList",
                        "isNonLocalGoto",
                        "()Z",
                        false);
                mv.visitJumpInsn(Opcodes.IFEQ, notControlFlow);  // Not marked, done

                // Marked: check if still TAILCALL
                mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                        "getControlFlowType",
                        "()Lorg/perlonjava/runtime/runtimetypes/ControlFlowType;",
                        false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/ControlFlowType",
                        "ordinal",
                        "()I",
                        false);
                mv.visitInsn(Opcodes.ICONST_4);  // TAILCALL.ordinal() = 4
                mv.visitJumpInsn(Opcodes.IF_ICMPEQ, tailcallLoop);  // Still TAILCALL, loop

                // Not TAILCALL - different marker (LAST/NEXT/REDO/GOTO), dispatch it
                mv.visitJumpInsn(Opcodes.GOTO, blockDispatcher);

                // Not TAILCALL initially - jump to block dispatcher
                mv.visitLabel(notTailcall);
                mv.visitJumpInsn(Opcodes.GOTO, blockDispatcher);

                // Not a control flow marker - load it back and continue
                mv.visitLabel(notControlFlow);
                mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);

                // If this is the first use of this dispatcher, emit it now
                if (isFirstUse) {
                    Label skipDispatcher = new Label();
                    mv.visitJumpInsn(Opcodes.GOTO, skipDispatcher);
                    EmitSubroutine.emitBlockDispatcher(mv, emitterVisitor, blockDispatcher, new JavaClassInfo.SpillRef[0]);
                    mv.visitLabel(skipDispatcher);
                }
            }

            if (pooledArgsArray) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }
            if (pooledSub) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }
            if (pooledMethod) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }
            if (pooledObject) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }
            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                // Transform the value in the stack to RuntimeScalar
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                // Remove the value from the stack
                emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
            }
        }
    }

    public static void handleArrowArrayDeref(EmitterVisitor emitterVisitor, BinaryOperatorNode node, String arrayOperation) {
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) ->[] ");
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        node.left.accept(scalarVisitor); // target - left parameter

        int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledLeft = leftSlot >= 0;
        if (!pooledLeft) {
            leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

        ArrayLiteralNode right = (ArrayLiteralNode) node.right;

        boolean isSingleRange = right.elements.size() == 1 &&
                right.elements.getFirst() instanceof BinaryOperatorNode binOp &&
                "..".equals(binOp.operator);

        if (right.elements.size() == 1 && !isSingleRange) {
            // Single index: use get/delete/exists methods
            Node elem = right.elements.getFirst();
            elem.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

            int indexSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledIndex = indexSlot >= 0;
            if (!pooledIndex) {
                indexSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, indexSlot);

            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, indexSlot);

            // Check if strict refs is enabled at compile time
            if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_STRICT_REFS)) {
                // Use strict version (throws error on symbolic references)
                String methodName = switch (arrayOperation) {
                    case "get" -> "arrayDerefGet";
                    case "delete" -> "arrayDerefDelete";
                    case "deleteLocal" -> "arrayDerefDeleteLocal";
                    case "exists" -> "arrayDerefExists";
                    default ->
                            throw new PerlCompilerException(node.tokenIndex, "Not implemented: array operation: " + arrayOperation, emitterVisitor.ctx.errorUtil);
                };
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                        methodName, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            } else {
                // Use non-strict version (allows symbolic references)
                String methodName = switch (arrayOperation) {
                    case "get" -> "arrayDerefGetNonStrict";
                    case "delete" -> "arrayDerefDeleteNonStrict";
                    case "deleteLocal" -> "arrayDerefDeleteLocalNonStrict";
                    case "exists" -> "arrayDerefExistsNonStrict";
                    default ->
                            throw new PerlCompilerException(node.tokenIndex, "Not implemented: array operation: " + arrayOperation, emitterVisitor.ctx.errorUtil);
                };
                // Push the current package name for symbolic reference resolution
                emitterVisitor.pushCurrentPackage();
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                        methodName, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            }

            if (pooledIndex) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }

            if (pooledLeft) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }

            // Only force FETCH for "get" operations - delete/exists can return null
            if (arrayOperation.equals("get")) {
                EmitOperator.handleVoidContextForTied(emitterVisitor);
            } else {
                EmitOperator.handleVoidContext(emitterVisitor);
            }
            return;
        } else {
            // Multiple indices: use slice method (only for get operation)
            if (!arrayOperation.equals("get")) {
                throw new PerlCompilerException(node.tokenIndex, "Array slice not supported for " + arrayOperation, emitterVisitor.ctx.errorUtil);
            }

            // Emit the indices as a RuntimeList
            ListNode nodeRight = right.asListNode();
            nodeRight.accept(emitterVisitor.with(RuntimeContextType.LIST));

            int indexListSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledIndexList = indexListSlot >= 0;
            if (!pooledIndexList) {
                indexListSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, indexListSlot);

            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, indexListSlot);

            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                    "arrayDerefGetSlice", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;", false);

            if (pooledIndexList) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }

            // Context conversion: list slice in scalar/void contexts
            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                // Convert RuntimeList to RuntimeScalar (Perl scalar slice semantics)
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeList",
                        "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
            }

            if (pooledLeft) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }
        }
        // No handleVoidContextForTied here - slices already handle void context with POP above
    }

    public static void handleArrowHashDeref(EmitterVisitor emitterVisitor, BinaryOperatorNode node, String hashOperation) {
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit(BinaryOperatorNode) ->{} " + node);
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        node.left.accept(scalarVisitor); // target - left parameter

        int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledLeft = leftSlot >= 0;
        if (!pooledLeft) {
            leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

        // emit the {0} as a RuntimeList
        ListNode nodeRight = ((HashLiteralNode) node.right).asListNode();

        if (!nodeRight.elements.isEmpty()) {
            Node nodeZero = nodeRight.elements.getFirst();
            if (nodeRight.elements.size() == 1 && nodeZero instanceof IdentifierNode) {
                // Convert IdentifierNode to StringNode:  {a} to {"a"}
                nodeRight.elements.set(0, new StringNode(((IdentifierNode) nodeZero).name, ((IdentifierNode) nodeZero).tokenIndex));
            }
        }

        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("visit -> (HashLiteralNode) autoquote " + node.right);
        nodeRight.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        int keySlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledKey = keySlot >= 0;
        if (!pooledKey) {
            keySlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, keySlot);

        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, keySlot);

        // Check if strict refs is enabled at compile time
        if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_STRICT_REFS)) {
            // Use strict version (throws error on symbolic references)
            String methodName = switch (hashOperation) {
                case "get" -> "hashDerefGet";
                case "getForLocal" -> "hashDerefGetForLocal";
                case "delete" -> "hashDerefDelete";
                case "deleteLocal" -> "hashDerefDeleteLocal";
                case "exists" -> "hashDerefExists";
                default ->
                        throw new PerlCompilerException(node.tokenIndex, "Not implemented: hash operation: " + hashOperation, emitterVisitor.ctx.errorUtil);
            };
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                    methodName, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
        } else {
            // Use non-strict version (allows symbolic references)
            String methodName = switch (hashOperation) {
                case "get" -> "hashDerefGetNonStrict";
                case "getForLocal" -> "hashDerefGetForLocalNonStrict";
                case "delete" -> "hashDerefDeleteNonStrict";
                case "deleteLocal" -> "hashDerefDeleteLocalNonStrict";
                case "exists" -> "hashDerefExistsNonStrict";
                default ->
                        throw new PerlCompilerException(node.tokenIndex, "Not implemented: hash operation: " + hashOperation, emitterVisitor.ctx.errorUtil);
            };
            // Push the current package name for symbolic reference resolution
            emitterVisitor.pushCurrentPackage();
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                    methodName, "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
        }

        if (pooledKey) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }

        if (pooledLeft) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }

        // Only force FETCH for "get" operations - delete/exists can return null
        if (hashOperation.equals("get") || hashOperation.equals("getForLocal")) {
            EmitOperator.handleVoidContextForTied(emitterVisitor);
        } else {
            EmitOperator.handleVoidContext(emitterVisitor);
        }
    }
}
