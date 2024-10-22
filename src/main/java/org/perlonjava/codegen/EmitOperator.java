package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.ScalarGlobOperator;

public class EmitOperator {

    // Handles the 'readdir' operator, which reads directory contents.
    static void handleReaddirOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        String operator = node.operator;
        // Accept the operand in SCALAR context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        emitterVisitor.pushCallContext();
        // Invoke the static method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/Operator",
                operator,
                "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'mkdir' operator, which creates directories.
    static void handleMkdirOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        String operator = node.operator;
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Invoke the static method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/Operator",
                operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'each' operator, which iterates over elements.
    static void handleEachOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        String operator = node.operator;
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Invoke the interface method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", operator, "()Lorg/perlonjava/runtime/RuntimeList;", true);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'keys' operator, which retrieves keys from a data structure.
    static void handleKeysOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        String operator = node.operator;
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Invoke the interface method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", operator, "()Lorg/perlonjava/runtime/RuntimeArray;", true);
        // Handle different context types.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.LIST) {
            // Do nothing for LIST context.
        } else if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            // Convert to scalar if in SCALAR context.
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
        } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            // Pop the result if in VOID context.
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'readline' operator for reading lines from a file handle.
    static void handleReadlineOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;

        // Emit the File Handle
        if (node.left instanceof OperatorNode) {
            // If the left node is an operator, accept it in SCALAR context.
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        } else {
            // Otherwise, emit the file handle directly.
            emitterVisitor.emitFileHandle(node.left);
        }

        if (operator.equals("readline")) {
            // Push call context for SCALAR or LIST context.
            emitterVisitor.pushCallContext();
            // Invoke the static method for the operator.
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        } else {
            // Invoke the static method for the operator without context.
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }

        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'say' operator for outputting data.
    static void handleSayOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        // Emit the argument list in LIST context.
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));

        // Emit the File Handle
        if (node.left instanceof OperatorNode) {
            // If the left node is an operator, accept it in SCALAR context.
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        } else {
            // Otherwise, emit the file handle directly.
            emitterVisitor.emitFileHandle(node.left);
        }

        // Call the operator, return Scalar
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the unary plus operator, which is a no-op in many contexts.
    static void handleUnaryPlusOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Accept the operand in SCALAR context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'index' built-in function, which finds the position of a substring.
    static void handleIndexBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);
        if (node.operand instanceof ListNode operand) {
            if (!operand.elements.isEmpty()) {
                // Accept the first two elements in SCALAR context.
                operand.elements.get(0).accept(scalarVisitor);
                operand.elements.get(1).accept(scalarVisitor);
                if (operand.elements.size() == 3) {
                    // Accept the third element if it exists.
                    operand.elements.get(2).accept(scalarVisitor);
                } else {
                    // Otherwise, use 'undef' as the third element.
                    new OperatorNode("undef", null, node.tokenIndex).accept(scalarVisitor);
                }
                // Invoke the virtual method for the operator.
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/RuntimeScalar",
                        node.operator,
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                // If the context is VOID, pop the result from the stack.
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    mv.visitInsn(Opcodes.POP);
                }
                return;
            }
        }
        // Throw an exception if the operator is not implemented.
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: operator: " + node.operator, emitterVisitor.ctx.errorUtil);
    }

    // Handles the 'atan2' function, which calculates the arctangent of two numbers.
    static void handleAtan2(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);
        if (node.operand instanceof ListNode operand) {
            if (operand.elements.size() == 2) {
                // Accept both elements in SCALAR context.
                operand.elements.get(0).accept(scalarVisitor);
                operand.elements.get(1).accept(scalarVisitor);
                // Invoke the virtual method for the operator.
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/RuntimeScalar",
                        node.operator,
                        "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                // If the context is VOID, pop the result from the stack.
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    mv.visitInsn(Opcodes.POP);
                }
                return;
            }
        }
        // Throw an exception if the operator is not implemented.
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: operator: " + node.operator, emitterVisitor.ctx.errorUtil);
    }

    // Handles the 'die' built-in function, which throws an exception.
    static void handleDieBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  die LIST
        //   static RuntimeDataProvider die(RuntimeDataProvider value, int ctx)
        String operator = node.operator;
        emitterVisitor.ctx.logDebug("handleDieBuiltin " + node);
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));

        // Push the formatted line number as a message.
        Node message = new StringNode(emitterVisitor.ctx.errorUtil.errorMessage(node.tokenIndex, ""), node.tokenIndex);
        message.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Invoke the static method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/Operator",
                operator,
                "(Lorg/perlonjava/runtime/RuntimeDataProvider;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'reverse' built-in function, which reverses a list.
    static void handleReverseBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  reverse LIST
        //   static RuntimeDataProvider reverse(RuntimeDataProvider value, int ctx)
        String operator = node.operator;
        emitterVisitor.ctx.logDebug("handleReverseBuiltin " + node);
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        emitterVisitor.pushCallContext();
        // Invoke the static method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeDataProvider;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'crypt' built-in function, which encrypts data.
    static void handleCryptBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  crypt PLAINTEXT,SALT
        emitterVisitor.ctx.logDebug("handleCryptBuiltin " + node);
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Invoke the static method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Crypt",
                node.operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;",
                false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'unpack' built-in function, which unpacks data from a string.
    static void handleUnpackBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  unpack TEMPLATE, EXPR
        emitterVisitor.ctx.logDebug("handleUnpackBuiltin " + node);
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Invoke the static method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Unpack",
                node.operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;",
                false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'pack' built-in function, which packs data into a string.
    static void handlePackBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  pack TEMPLATE, LIST
        emitterVisitor.ctx.logDebug("handlePackBuiltin " + node);
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Invoke the static method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Pack",
                node.operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;",
                false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'splice' built-in function, which modifies an array.
    static void handleSpliceBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Handle:  splice @array, LIST
        String operator = node.operator;
        emitterVisitor.ctx.logDebug("handleSpliceBuiltin " + node);
        Node args = node.operand;
        // Remove the first element from the list and accept it in LIST context.
        Node operand = ((ListNode) args).elements.remove(0);
        operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Accept the remaining arguments in LIST context.
        args.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Invoke the static method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeArray;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'push' operator, which adds elements to an array.
    static void handlePushOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        // Accept both left and right operands in LIST context.
        node.left.accept(emitterVisitor.with(RuntimeContextType.LIST));
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Invoke the virtual method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", operator, "(Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'map' operator, which applies a function to each element of a list.
    static void handleMapOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        // Accept the right operand in LIST context and the left operand in SCALAR context.
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));  // list
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR)); // subroutine
        // Invoke the static method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'split' operator, which splits a string into a list.
    static void handleSplitOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        // Accept the left operand in SCALAR context and the right operand in LIST context.
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Invoke the static method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'join' operator, which joins elements of a list into a string.
    static void handleJoinOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        // Accept the left operand in SCALAR context and the right operand in LIST context.
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Invoke the static method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'diamond' operator, which reads input from a file or standard input.
    static void handleDiamondBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        String argument = ((StringNode) ((ListNode) node.operand).elements.get(0)).value;
        emitterVisitor.ctx.logDebug("visit diamond " + argument);
        if (argument.equals("") || argument.equals("<>")) {
            // Handle null filehandle:  <>  <<>>
            node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            emitterVisitor.pushCallContext();
            // Invoke the static method for reading lines.
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/DiamondIO",
                    "readline",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
            // If the context is VOID, pop the result from the stack.
            if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                mv.visitInsn(Opcodes.POP);
            }
        } else {
            // Handle globbing if the argument is not empty or "<>".
            node.operator = "glob";
            handleGlobBuiltin(emitterVisitor, node);
        }
    }

    // Handles the 'chomp' built-in function, which removes trailing newlines from strings.
    static void handleChompBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Invoke the interface method for the operator.
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "org/perlonjava/runtime/RuntimeDataProvider",
                node.operator,
                "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'glob' built-in function, which performs filename expansion.
    static void handleGlobBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Generate unique IDs for this glob instance
        int globId = ScalarGlobOperator.currentId++;

        // public static RuntimeDataProvider evaluate(id, patternArg, ctx)
        mv.visitLdcInsn(globId);
        // Accept the operand in SCALAR context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        emitterVisitor.pushCallContext();
        // Invoke the static method for evaluating the glob pattern.
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/ScalarGlobOperator", "evaluate", "(ILorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);

        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'vec' built-in function, which manipulates bits in a string.
    static void handleVecBuiltin(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        // Accept the operand in LIST context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Invoke the static method for the operator.
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/Vec",
                node.operator,
                "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'range' operator, which creates a range of values.
    static void handleRangeOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // Accept both left and right operands in SCALAR context.
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        // Invoke the static method to create a range.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/PerlRange",
                "createRange",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/PerlRange;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'substr' operator, which extracts a substring from a string.
    static void handleSubstr(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        String operator = node.operator;
        // Accept the left operand in SCALAR context and the right operand in LIST context.
        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        // Invoke the static method for the operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator", operator, "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'repeat' operator, which repeats a string or list a specified number of times.
    static void handleRepeat(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        Node left = node.left;
        // Determine the context for the left operand.
        if (node.left instanceof ListNode) {
            node.left.accept(emitterVisitor.with(RuntimeContextType.LIST));
        } else {
            node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        }
        // Accept the right operand in SCALAR context.
        node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        emitterVisitor.pushCallContext();
        // Invoke the static method for the 'repeat' operator.
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/Operator",
                "repeat",
                "(Lorg/perlonjava/runtime/RuntimeDataProvider;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'concat' operator, which concatenates two strings.
    static void handleConcatOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        // Accept both left and right operands in SCALAR context.
        node.left.accept(scalarVisitor); // target - left parameter
        node.right.accept(scalarVisitor); // right parameter
        // Invoke the virtual method for string concatenation.
        emitterVisitor.ctx.mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeScalar",
                "stringConcat",
                "(Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'scalar' operator, which forces a list into scalar context.
    static void handleScalar(EmitterVisitor emitterVisitor, OperatorNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        // Accept the operand in SCALAR context.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        // Invoke the interface method to convert to scalar.
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'local' operator.
    static void handleLocal(EmitterVisitor emitterVisitor, OperatorNode node) {
        // emit the lvalue
        int lvalueContext = LValueVisitor.getContext(node.operand);
        node.operand.accept(emitterVisitor.with(lvalueContext));
        // save the old value
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/codegen/DynamicVariableManager",
                "pushLocalVariable",
                "(Lorg/perlonjava/runtime/RuntimeBaseEntity;)Lorg/perlonjava/runtime/RuntimeBaseEntity;",
                false);
        // If the context is VOID, pop the result from the stack.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    // Handles the 'delete' and 'exists' operators for hash elements.
    static void handleDeleteExists(EmitterVisitor emitterVisitor, OperatorNode node) {
        //   OperatorNode: delete
        //    ListNode:
        //      BinaryOperatorNode: {
        //        OperatorNode: $
        //          IdentifierNode: a
        //        HashLiteralNode:
        //          NumberNode: 10
        String operator = node.operator;
        if (node.operand instanceof ListNode operand) {
            if (operand.elements.size() == 1) {
                BinaryOperatorNode binop = (BinaryOperatorNode) operand.elements.get(0);
                if (binop.operator.equals("{")) {
                    // Handle hash element operator.
                    emitterVisitor.handleHashElementOperator(binop, operator);
                    return;
                }
                if (binop.operator.equals("->")) {
                    if (binop.right instanceof HashLiteralNode) { // ->{x}
                        // Handle arrow hash dereference
                        emitterVisitor.handleArrowHashDeref(binop, operator);
                        return;
                    }
                }
            }
        }
        // Throw an exception if the operator is not implemented.
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: operator: " + operator, emitterVisitor.ctx.errorUtil);
    }

    // Handles the 'package' operator, which sets the current package for the symbol table.
    static void handlePackageOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        // Extract the package name from the operand.
        String name = ((IdentifierNode) node.operand).name;
        // Set the current package in the symbol table.
        emitterVisitor.ctx.symbolTable.setCurrentPackage(name);
        // Set debug information for the file name.
        DebugInfo.setDebugInfoFileName(emitterVisitor.ctx);
        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
            // If context is not void, return an empty list.
            ListNode listNode = new ListNode(node.tokenIndex);
            listNode.accept(emitterVisitor);
        }
    }

    // Handles the 'next', 'last', and 'redo' operators for loop control.
    static void handleNextOperator(EmitterContext ctx, OperatorNode node) {
        ctx.logDebug("visit(next)");

        String labelStr = null;
        ListNode labelNode = (ListNode) node.operand;
        if (labelNode.elements.isEmpty()) {
            // Handle 'next' without a label.
        } else {
            // Handle 'next' with a label.
            Node arg = labelNode.elements.get(0);
            if (arg instanceof IdentifierNode) {
                // Extract the label name.
                labelStr = ((IdentifierNode) arg).name;
            } else {
                throw new RuntimeException("Not implemented: " + node);
            }
        }

        String operator = node.operator;
        // Find loop labels by name.
        LoopLabels loopLabels = ctx.javaClassInfo.findLoopLabelsByName(labelStr);
        ctx.logDebug("visit(next) operator: " + operator + " label: " + labelStr + " labels: " + loopLabels);
        if (loopLabels == null) {
            throw new PerlCompilerException(node.tokenIndex, "Can't \"" + operator + "\" outside a loop block", ctx.errorUtil);
        }

        ctx.logDebug("visit(next): asmStackLevel: " + ctx.javaClassInfo.stackLevelManager.getStackLevel());

        // Use StackLevelManager to emit POP instructions
        ctx.javaClassInfo.stackLevelManager.emitPopInstructions(ctx.mv, loopLabels.asmStackLevel);

        // Determine the appropriate label to jump to.
        Label label = operator.equals("next") ? loopLabels.nextLabel
                : operator.equals("last") ? loopLabels.lastLabel
                : loopLabels.redoLabel;
        ctx.mv.visitJumpInsn(Opcodes.GOTO, label);
    }

    // Handles the 'return' operator, which exits a subroutine and returns a value.
    static void handleReturnOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;

        ctx.logDebug("visit(return) in context " + emitterVisitor.ctx.contextType);
        ctx.logDebug("visit(return) will visit " + node.operand + " in context " + emitterVisitor.ctx.with(RuntimeContextType.RUNTIME).contextType);

        // Use StackLevelManager to emit POP instructions
        ctx.javaClassInfo.stackLevelManager.emitPopInstructions(ctx.mv, 0);

        if (node.operand instanceof ListNode list) {
            if (list.elements.size() == 1) {
                // Special case for a list with 1 element.
                list.elements.getFirst().accept(emitterVisitor.with(RuntimeContextType.RUNTIME));
                emitterVisitor.ctx.mv.visitJumpInsn(Opcodes.GOTO, emitterVisitor.ctx.javaClassInfo.returnLabel);
                return;
            }
        }

        // Accept the operand in RUNTIME context and jump to the return label.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.RUNTIME));
        emitterVisitor.ctx.mv.visitJumpInsn(Opcodes.GOTO, emitterVisitor.ctx.javaClassInfo.returnLabel);
        // TODO return (1,2), 3
    }
}
