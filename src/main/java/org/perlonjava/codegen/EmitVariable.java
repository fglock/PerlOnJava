package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.runtime.*;

import java.util.ArrayList;
import java.util.List;

public class EmitVariable {
    private static void fetchGlobalVariable(EmitterContext ctx, boolean createIfNotExists, String sigil, String varName, int tokenIndex) {

        String var = NameNormalizer.normalizeVariableName(varName, ctx.symbolTable.getCurrentPackage());
        ctx.logDebug("GETVAR lookup global " + sigil + varName + " normalized to " + var + " createIfNotExists:" + createIfNotExists);

        if (sigil.equals("$") && (createIfNotExists || GlobalContext.existsGlobalVariable(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalContext",
                    "getGlobalVariable",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else if (sigil.equals("@") && (createIfNotExists || GlobalContext.existsGlobalArray(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalContext",
                    "getGlobalArray",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeArray;",
                    false);
        } else if (sigil.equals("%") && (createIfNotExists || GlobalContext.existsGlobalHash(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalContext",
                    "getGlobalHash",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeHash;",
                    false);
        } else {
            // variable not found
            System.err.println(
                    ctx.errorUtil.errorMessage(tokenIndex,
                            "Warning: Global symbol \""
                                    + sigil + varName
                                    + "\" requires explicit package name (did you forget to declare \"my "
                                    + sigil + varName
                                    + "\"?)"));
        }
    }

    static void handleVariableOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        String sigil = node.operator;
        MethodVisitor mv = emitterVisitor.ctx.mv;
        if (node.operand instanceof IdentifierNode) { // $a @a %a
            String name = ((IdentifierNode) node.operand).name;
            emitterVisitor.ctx.logDebug("GETVAR " + sigil + name);

            if (sigil.equals("*")) {
                // typeglob
                String fullName = NameNormalizer.normalizeVariableName(name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeGlob");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(fullName); // emit string
                mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        "org/perlonjava/runtime/RuntimeGlob",
                        "<init>",
                        "(Ljava/lang/String;)V",
                        false); // Call new RuntimeGlob(String)
                return;
            }

            if (sigil.equals("&")) {
                // Code
                String fullName = NameNormalizer.normalizeVariableName(name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
                mv.visitLdcInsn(fullName); // emit string
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/GlobalContext",
                        "getGlobalCodeRef",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                        false);
                return;
            }

            int varIndex = emitterVisitor.ctx.symbolTable.getVariableIndex(sigil + name);
            if (varIndex == -1) {
                // not a declared `my` or `our` variable
                // Fetch a global variable.
                // Autovivify if the name is fully qualified, or if it is a regex variable like `$1`
                // TODO special variables: `$,` `$$`
                boolean createIfNotExists = name.contains("::") || ScalarUtils.isInteger(name);
                fetchGlobalVariable(emitterVisitor.ctx, createIfNotExists, sigil, name, node.getIndex());
            } else {
                // retrieve the `my` or `our` variable from local vars
                mv.visitVarInsn(Opcodes.ALOAD, varIndex);
            }
            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR && !sigil.equals("$")) {
                // scalar context: transform the value in the stack to scalar
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
            }
            emitterVisitor.ctx.logDebug("GETVAR end " + varIndex);
            return;
        }
        switch (sigil) {
            case "@":
                // `@$a`
                emitterVisitor.ctx.logDebug("GETVAR `@$a`");
                node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "arrayDeref", "()Lorg/perlonjava/runtime/RuntimeArray;", false);
                return;
            case "%":
                // `%$a`
                emitterVisitor.ctx.logDebug("GETVAR `%$a`");
                node.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "hashDeref", "()Lorg/perlonjava/runtime/RuntimeHash;", false);
                return;
            case "$":
                // `$$a`
                emitterVisitor.ctx.logDebug("GETVAR `$$a`");
                node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "scalarDeref", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
                return;
            case "*":
                // `*$a`
                emitterVisitor.ctx.logDebug("GETVAR `*$a`");
                node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "globDeref", "()Lorg/perlonjava/runtime/RuntimeGlob;", false);
                return;
            case "&":
                // `&$a`
                emitterVisitor.ctx.logDebug("GETVAR `&$a`");
                node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                mv.visitVarInsn(Opcodes.ALOAD, 1);  // push @_ to stack
                emitterVisitor.pushCallContext();   // push call context to stack
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/RuntimeScalar",
                        "apply",
                        "(Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
                        false); // generate an .apply() call
                return;
        }

        // TODO ${a} ${[ 123 ]}
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: " + sigil, emitterVisitor.ctx.errorUtil);
    }

    static void handleAssignOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        emitterVisitor.ctx.logDebug("SET " + node);
        MethodVisitor mv = emitterVisitor.ctx.mv;
        // Determine the assign type based on the left side.
        // Inspect the AST and get the L-value context: SCALAR or LIST
        int lvalueContext = LValueVisitor.getContext(node);
        emitterVisitor.ctx.logDebug("SET Lvalue context: " + lvalueContext);
        // Execute the right side first: assignment is right-associative
        switch (lvalueContext) {
            case RuntimeContextType.SCALAR:
                emitterVisitor.ctx.logDebug("SET right side scalar");

                // TODO - special case where the left value is an operator or subroutine call:
                //   `pos`, `substr`, `vec`, `sub :lvalue`

                node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR));   // emit the value
                node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));   // emit the variable

                boolean isGlob = false;
                String leftDescriptor = "org/perlonjava/runtime/RuntimeScalar";
                if (node.left instanceof OperatorNode && ((OperatorNode) node.left).operator.equals("*")) {
                    leftDescriptor = "org/perlonjava/runtime/RuntimeGlob";
                    isGlob = true;
                }
                String rightDescriptor = "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;";
                if (node.right instanceof OperatorNode && ((OperatorNode) node.right).operator.equals("*")) {
                    rightDescriptor = "(Lorg/perlonjava/runtime/RuntimeGlob;)Lorg/perlonjava/runtime/RuntimeScalar;";
                    isGlob = true;
                }
                if (isGlob) {
                    mv.visitInsn(Opcodes.SWAP); // move the target first
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, leftDescriptor, "set", rightDescriptor, false);
                } else {
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "addToScalar", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", true);
                }
                break;
            case RuntimeContextType.LIST:
                emitterVisitor.ctx.logDebug("SET right side list");
                Node nodeRight = node.right;
                // make sure the right node is a ListNode
                if (!(nodeRight instanceof ListNode)) {
                    List<Node> elements = new ArrayList<>();
                    elements.add(nodeRight);
                    nodeRight = new ListNode(elements, node.tokenIndex);
                }
                nodeRight.accept(emitterVisitor.with(RuntimeContextType.LIST));   // emit the value
                node.left.accept(emitterVisitor.with(RuntimeContextType.LIST));   // emit the variable
                mv.visitInsn(Opcodes.SWAP); // move the target first
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "setFromList", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeArray;", true);
                break;
            default:
                throw new PerlCompilerException("Unsupported assignment context: " + lvalueContext);
        }
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            // Remove the value from the stack
            mv.visitInsn(Opcodes.POP);
        }
        emitterVisitor.ctx.logDebug("SET end");
    }

    static void handleMyOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        String operator = node.operator;
        if (node.operand instanceof ListNode listNode) { // my ($a, $b)  our ($a, $b)
            // process each item of the list; then returns the list
            for (Node element : listNode.elements) {
                if (element instanceof OperatorNode && "undef".equals(((OperatorNode) element).operator)) {
                    continue; // skip "undef"
                }
                OperatorNode myNode = new OperatorNode(operator, element, listNode.tokenIndex);
                myNode.accept(emitterVisitor.with(RuntimeContextType.VOID));
            }
            if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                listNode.accept(emitterVisitor);
            }
            return;
        } else if (node.operand instanceof OperatorNode sigilNode) { //  [my our] followed by [$ @ %]
            String sigil = sigilNode.operator;
            if ("$@%".contains(sigil)) {
                Node identifierNode = sigilNode.operand;
                if (identifierNode instanceof IdentifierNode) { // my $a
                    String name = ((IdentifierNode) identifierNode).name;
                    String var = sigil + name;
                    emitterVisitor.ctx.logDebug("MY " + operator + " " + sigil + name);
                    if (emitterVisitor.ctx.symbolTable.getVariableIndexInCurrentScope(var) != -1) {
                        System.err.println(
                                emitterVisitor.ctx.errorUtil.errorMessage(node.getIndex(),
                                        "Warning: \"" + operator + "\" variable "
                                                + var
                                                + " masks earlier declaration in same ctx.symbolTable"));
                    }
                    int varIndex = emitterVisitor.ctx.symbolTable.addVariable(var);
                    // TODO optimization - SETVAR+MY can be combined

                    // Determine the class name based on the sigil
                    String className = EmitterMethodCreator.getVariableClassName(sigil);

                    if (operator.equals("my")) {
                        // "my":
                        // Create a new instance of the determined class
                        emitterVisitor.ctx.mv.visitTypeInsn(Opcodes.NEW, className);
                        emitterVisitor.ctx.mv.visitInsn(Opcodes.DUP);
                        emitterVisitor.ctx.mv.visitMethodInsn(
                                Opcodes.INVOKESPECIAL,
                                className,
                                "<init>",
                                "()V",
                                false);
                    } else {
                        // "our":
                        // Create and fetch a global variable
                        fetchGlobalVariable(emitterVisitor.ctx, true, sigil, name, node.getIndex());
                    }
                    if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                        emitterVisitor.ctx.mv.visitInsn(Opcodes.DUP);
                    }
                    // Store in a JVM local variable
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, varIndex);
                    if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR && !sigil.equals("$")) {
                        // scalar context: transform the value in the stack to scalar
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
                    }
                    return;
                }
            }
        }
        throw new PerlCompilerException(
                node.tokenIndex, "Not implemented: " + node.operator, emitterVisitor.ctx.errorUtil);
    }
}
