package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.astvisitor.LValueVisitor;
import org.perlonjava.perlmodule.Warnings;
import org.perlonjava.runtime.*;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.perlmodule.Strict.STRICT_REFS;
import static org.perlonjava.perlmodule.Strict.STRICT_VARS;

public class EmitVariable {
    private static void fetchGlobalVariable(EmitterContext ctx, boolean createIfNotExists, String sigil, String varName, int tokenIndex) {

        String var = NameNormalizer.normalizeVariableName(varName, ctx.symbolTable.getCurrentPackage());
        ctx.logDebug("GETVAR lookup global " + sigil + varName + " normalized to " + var + " createIfNotExists:" + createIfNotExists);

        if (sigil.equals("$") && (createIfNotExists || GlobalVariable.existsGlobalVariable(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalVariable",
                    "getGlobalVariable",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
            return;
        }

        if (sigil.equals("@") && (createIfNotExists || GlobalVariable.existsGlobalArray(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalVariable",
                    "getGlobalArray",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeArray;",
                    false);
            return;
        }

        if (sigil.equals("%") && var.endsWith("::")) {
            // A stash
            // Stash is the hash that represents a package's symbol table,
            // containing all the typeglobs for that package.
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/HashSpecialVariable",
                    "getStash",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeHash;",
                    false);
            return;
        }

        if (sigil.equals("%") && (createIfNotExists || GlobalVariable.existsGlobalHash(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalVariable",
                    "getGlobalHash",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeHash;",
                    false);
            return;
        }

        // variable not found
        throw new PerlCompilerException(
                tokenIndex,
                "Global symbol \""
                        + sigil + varName
                        + "\" requires explicit package name (did you forget to declare \"my "
                        + sigil + varName
                        + "\"?)",
                ctx.errorUtil);
    }

    static void handleVariableOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        String sigil = node.operator;
        MethodVisitor mv = emitterVisitor.ctx.mv;
        if (node.operand instanceof IdentifierNode identifierNode) { // $a @a %a
            String name = identifierNode.name;
            emitterVisitor.ctx.logDebug("GETVAR " + sigil + name);

            if (sigil.equals("*")) {
                // typeglob
                String fullName = NameNormalizer.normalizeVariableName(name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
                mv.visitLdcInsn(fullName); // emit string
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/GlobalVariable",
                        "getGlobalIO",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeGlob;",
                        false);
                return;
            }

            if (sigil.equals("&")) {
                // Code
                String fullName = NameNormalizer.normalizeVariableName(name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
                mv.visitLdcInsn(fullName); // emit string
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/GlobalVariable",
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
                boolean createIfNotExists = name.contains("::") // Fully qualified name
                        || ScalarUtils.isInteger(name)  // Regex variable always exists
                        || !emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(STRICT_VARS);  // `no strict "vars"`
                fetchGlobalVariable(emitterVisitor.ctx, createIfNotExists, sigil, name, node.getIndex());
            } else {
                // retrieve the `my` or `our` variable from local vars
                mv.visitVarInsn(Opcodes.ALOAD, varIndex);
            }
            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR && !sigil.equals("$")) {
                // scalar context: transform the value in the stack to scalar
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
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
                if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
                }
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
                if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(STRICT_REFS)) {
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "scalarDeref", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
                } else {
                    // no strict refs
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.pushCurrentPackage();
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "scalarDerefNonStrict", "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                }
                return;
            case "*":
                // `*$a`
                emitterVisitor.ctx.logDebug("GETVAR `*$a`");
                if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(STRICT_REFS)) {
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "globDeref", "()Lorg/perlonjava/runtime/RuntimeGlob;", false);
                } else {
                    // no strict refs
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.pushCurrentPackage();
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "globDerefNonStrict", "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeGlob;", false);
                }
                return;
            case "&":
                // `&$a`
                emitterVisitor.ctx.logDebug("GETVAR `&$a`");
                node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                mv.visitVarInsn(Opcodes.ALOAD, 1);  // push @_ to stack
                emitterVisitor.pushCallContext();   // push call context to stack
                mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/RuntimeCode",
                        "apply",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
                        false); // generate an .apply() call
                return;
        }

        // TODO ${a} ${[ 123 ]}
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: " + sigil, emitterVisitor.ctx.errorUtil);
    }

    static void handleAssignOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;

        ctx.logDebug("SET " + node);
        MethodVisitor mv = ctx.mv;
        // Determine the assign type based on the left side.
        // Inspect the AST and get the L-value context: SCALAR or LIST
        int lvalueContext = LValueVisitor.getContext(node);
        ctx.logDebug("SET Lvalue context: " + lvalueContext);
        // Execute the right side first: assignment is right-associative
        switch (lvalueContext) {
            case RuntimeContextType.SCALAR:
                ctx.logDebug("SET right side scalar");

                if (node.left instanceof OperatorNode operatorNode && operatorNode.operator.equals("state")) {
                    emitStateInitialization(emitterVisitor, node, operatorNode, ctx);
                    break;
                }

                // The left value can be a variable, an operator or a subroutine call:
                //   `pos`, `substr`, `vec`, `sub :lvalue`

                node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR));   // emit the value
                node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));   // emit the variable

                OperatorNode nodeLeft = null;
                if (node.left instanceof OperatorNode operatorNode) {
                    nodeLeft = operatorNode;
                    if (nodeLeft.operator.equals("local") && nodeLeft.operand instanceof OperatorNode localNode) {
                        nodeLeft = localNode;  // local *var = ...
                    }

                    if (nodeLeft.operator.equals("keys")) {
                        // `keys %x = 3`   lvalue keys is a no-op
                        mv.visitInsn(Opcodes.SWAP); // move the target first
                        mv.visitInsn(Opcodes.POP);
                        break;
                    }

                    if (nodeLeft.operator.equals("\\")) {
                        // `\$b = \$a` requires "refaliasing"
                        throw new PerlCompilerException(node.tokenIndex, "Experimental aliasing via reference not enabled", ctx.errorUtil);
                    }
                }

                boolean isGlob = false;
                String leftDescriptor = "org/perlonjava/runtime/RuntimeScalar";
                if (nodeLeft != null && nodeLeft.operator.equals("*")) {
                    // glob:  *var
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
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "addToScalar", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                }
                break;
            case RuntimeContextType.LIST:
                emitterVisitor.ctx.logDebug("SET right side list");

                if (node.left instanceof OperatorNode operatorNode && operatorNode.operator.equals("state")) {
                    emitStateInitialization(emitterVisitor, node, operatorNode, ctx);
                    break;
                }

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
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "setFromList", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeArray;", false);
                EmitOperator.handleScalarContext(emitterVisitor, node);
                break;
            default:
                throw new PerlCompilerException(node.tokenIndex, "Unsupported assignment context: " + lvalueContext, ctx.errorUtil);
        }
        EmitOperator.handleVoidContext(emitterVisitor);
        emitterVisitor.ctx.logDebug("SET end");
    }

    private static void emitStateInitialization(EmitterVisitor emitterVisitor, BinaryOperatorNode node, OperatorNode operatorNode, EmitterContext ctx) {
        // This is a state variable initialization, it should run exactly once.
        ctx.logDebug("handleAssignOperator initialize state variable " + operatorNode);
        OperatorNode varNode = (OperatorNode) operatorNode.operand;
        IdentifierNode nameNode = (IdentifierNode) varNode.operand;
        String sigil = varNode.operator;

        // Emit: state $var // initializeState(id, value)
        int tokenIndex = node.tokenIndex;

        operatorNode.accept(emitterVisitor.with(RuntimeContextType.VOID));

        Node testStateVariable = new BinaryOperatorNode(
                "(",
                new OperatorNode(
                        "&",
                        new IdentifierNode("Internals::is_initialized_state_variable", tokenIndex),
                        tokenIndex
                ),
                ListNode.makeList(
                        new OperatorNode("__SUB__", null, tokenIndex),
                        new StringNode(sigil + nameNode.name, tokenIndex),
                        new NumberNode(String.valueOf(varNode.id), tokenIndex)
                ),
                tokenIndex
        );
        ctx.logDebug("handleAssignOperator initialize state variable " + testStateVariable);
        // testStateVariable.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Determine the method to call and its descriptor based on the sigil
        String methodName = switch (sigil) {
            case "$" -> "Internals::initialize_state_variable";
            case "@" -> "Internals::initialize_state_array";
            case "%" -> "Internals::initialize_state_hash";
            default ->
                    throw new PerlCompilerException(node.tokenIndex, "Unsupported variable type: " + sigil, ctx.errorUtil);
        };
        Node initStateVariable = new BinaryOperatorNode(
                "(",
                new OperatorNode(
                        "&",
                        new IdentifierNode(methodName, tokenIndex),
                        tokenIndex
                ),
                ListNode.makeList(
                        new OperatorNode("__SUB__", null, tokenIndex),
                        new StringNode(sigil + nameNode.name, tokenIndex),
                        new NumberNode(String.valueOf(varNode.id), tokenIndex),
                        node.right
                ),
                tokenIndex
        );
        ctx.logDebug("handleAssignOperator initialize state variable " + initStateVariable);
        // initStateVariable.accept(emitterVisitor.with(RuntimeContextType.VOID));

        new BinaryOperatorNode("||", testStateVariable, initStateVariable, tokenIndex)
                .accept(emitterVisitor.with(RuntimeContextType.VOID));

        varNode.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
    }

    static void handleMyOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;

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
                        if (Warnings.warningManager.isWarningEnabled("redefine")) {
                            System.err.println(
                                    emitterVisitor.ctx.errorUtil.errorMessage(node.getIndex(),
                                            "Warning: \"" + operator + "\" variable "
                                                    + var
                                                    + " masks earlier declaration in same ctx.symbolTable"));
                        }
                    }
                    int varIndex = emitterVisitor.ctx.symbolTable.addVariable(var, operator, sigilNode);
                    // TODO optimization - SETVAR+MY can be combined

                    // Determine the class name based on the sigil
                    String className = EmitterMethodCreator.getVariableClassName(sigil);

                    if (operator.equals("my")) {
                        // "my":
                        if (sigilNode.id == 0) {
                            // Create a new instance of the determined class
                            ctx.mv.visitTypeInsn(Opcodes.NEW, className);
                            ctx.mv.visitInsn(Opcodes.DUP);
                            ctx.mv.visitMethodInsn(
                                    Opcodes.INVOKESPECIAL,
                                    className,
                                    "<init>",
                                    "()V",
                                    false);
                        } else {
                            // The variable was initialized by a BEGIN block

                            // Determine the method to call and its descriptor based on the sigil
                            String methodName;
                            String methodDescriptor;
                            switch (var.charAt(0)) {
                                case '$' -> {
                                    methodName = "retrieveBeginScalar";
                                    methodDescriptor = "(Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeScalar;";
                                }
                                case '@' -> {
                                    methodName = "retrieveBeginArray";
                                    methodDescriptor = "(Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeArray;";
                                }
                                case '%' -> {
                                    methodName = "retrieveBeginHash";
                                    methodDescriptor = "(Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeHash;";
                                }
                                default ->
                                        throw new PerlCompilerException(node.tokenIndex, "Unsupported variable type: " + var.charAt(0), ctx.errorUtil);
                            }

                            ctx.mv.visitLdcInsn(var);
                            ctx.mv.visitLdcInsn(sigilNode.id);
                            ctx.mv.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    "org/perlonjava/runtime/PersistentVariable",
                                    methodName,
                                    methodDescriptor,
                                    false);
                        }
                    } else if (operator.equals("state")) {
                        // "state":

                        // Determine the method to call and its descriptor based on the sigil
                        String methodName;
                        String methodDescriptor;
                        switch (var.charAt(0)) {
                            case '$' -> {
                                methodName = "retrieveStateScalar";
                                methodDescriptor = "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeScalar;";
                            }
                            case '@' -> {
                                methodName = "retrieveStateArray";
                                methodDescriptor = "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeArray;";
                            }
                            case '%' -> {
                                methodName = "retrieveStateHash";
                                methodDescriptor = "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeHash;";
                            }
                            default ->
                                    throw new PerlCompilerException(node.tokenIndex, "Unsupported variable type: " + var.charAt(0), ctx.errorUtil);
                        }

                        Node codeRef = new OperatorNode("__SUB__", null, node.tokenIndex);
                        codeRef.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

                        ctx.mv.visitLdcInsn(var);
                        ctx.mv.visitLdcInsn(sigilNode.id);
                        ctx.mv.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "org/perlonjava/runtime/StateVariable",
                                methodName,
                                methodDescriptor,
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
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
                    }
                    return;
                }
            }
        }
        throw new PerlCompilerException(
                node.tokenIndex, "Not implemented: " + node.operator, emitterVisitor.ctx.errorUtil);
    }
}
