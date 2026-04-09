package org.perlonjava.backend.bytecode;

import org.perlonjava.frontend.analysis.ConstantFoldingVisitor;
import org.perlonjava.frontend.analysis.LValueVisitor;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.util.ArrayList;
import java.util.List;

public class CompileAssignment {

    private static boolean handleLocalAssignment(BytecodeCompiler bc, BinaryOperatorNode node, OperatorNode leftOp, int rhsContext) {
        if (!leftOp.operator.equals("local")) return false;
        Node localOperand = leftOp.operand;
        // General fallback for any BinaryOperatorNode lvalue (matches JVM backend behavior)
        // Handles: local $hash{key} = v, local $array[i] = v, local $obj->method->{key} = v, etc.
        if (localOperand instanceof BinaryOperatorNode binOp) {
            bc.compileNode(binOp, -1, rhsContext);
            int elemReg = bc.lastResultReg;
            bc.emit(Opcodes.PUSH_LOCAL_VARIABLE);
            bc.emitReg(elemReg);
            bc.compileNode(node.right, -1, rhsContext);
            int valueReg = bc.lastResultReg;
            bc.emit(Opcodes.SET_SCALAR);
            bc.emitReg(elemReg);
            bc.emitReg(valueReg);
            bc.lastResultReg = elemReg;
            return true;
        }
        if (localOperand instanceof OperatorNode sigilOp) {
            String sigil = sigilOp.operator;
            if ((sigil.equals("$") || sigil.equals("@") || sigil.equals("%") || sigil.equals("*"))
                    && sigilOp.operand instanceof IdentifierNode idNode) {
                String varName = sigil + idNode.name;
                if (bc.hasVariable(varName) && !bc.isOurVariable(varName) && !bc.isReservedVariable(varName)) {
                    bc.throwCompilerException("Can't localize lexical variable " + varName);
                    return true;
                }
                bc.compileNode(node.right, -1, rhsContext);
                int valueReg = bc.lastResultReg;
                String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalVarName);
                int localReg = bc.allocateRegister();
                switch (sigil) {
                    case "$" -> {
                        bc.emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                        bc.emitReg(localReg);
                        bc.emit(nameIdx);
                        bc.emit(Opcodes.SET_SCALAR);
                        bc.emitReg(localReg);
                        bc.emitReg(valueReg);
                    }
                    case "@" -> {
                        // For reserved variables like @_, use register-based localization
                        if (bc.isReservedVariable(varName)) {
                            int regIdx = bc.getVariableRegister(varName);
                            // If RHS and LHS use the same register (e.g. local @_ = @_),
                            // PUSH_LOCAL_VARIABLE would clear the array before ARRAY_SET_FROM_LIST
                            // can read from it. Copy RHS to a temp register first.
                            int srcReg = valueReg;
                            if (valueReg == regIdx) {
                                srcReg = bc.allocateRegister();
                                bc.emit(Opcodes.NEW_ARRAY);
                                bc.emitReg(srcReg);
                                bc.emit(Opcodes.ARRAY_SET_FROM_LIST);
                                bc.emitReg(srcReg);
                                bc.emitReg(valueReg);
                            }
                            bc.emit(Opcodes.PUSH_LOCAL_VARIABLE);
                            bc.emitReg(regIdx);
                            bc.emit(Opcodes.ARRAY_SET_FROM_LIST);
                            bc.emitReg(regIdx);
                            bc.emitReg(srcReg);
                            bc.lastResultReg = regIdx;
                            return true;
                        }
                        bc.emitWithToken(Opcodes.LOCAL_ARRAY, node.getIndex());
                        bc.emitReg(localReg);
                        bc.emit(nameIdx);
                        bc.emit(Opcodes.ARRAY_SET_FROM_LIST);
                        bc.emitReg(localReg);
                        bc.emitReg(valueReg);
                    }
                    case "%" -> {
                        bc.emitWithToken(Opcodes.LOCAL_HASH, node.getIndex());
                        bc.emitReg(localReg);
                        bc.emit(nameIdx);
                        bc.emit(Opcodes.HASH_SET_FROM_LIST);
                        bc.emitReg(localReg);
                        bc.emitReg(valueReg);
                    }
                    case "*" -> {
                        bc.emitWithToken(Opcodes.LOCAL_GLOB, node.getIndex());
                        bc.emitReg(localReg);
                        bc.emit(nameIdx);
                        bc.emit(Opcodes.STORE_GLOB);
                        bc.emitReg(localReg);
                        bc.emitReg(valueReg);
                    }
                }
                bc.lastResultReg = localReg;
                return true;
            }
            // Handle dynamic glob names: local *$probe = sub { ... }
            if (sigil.equals("*") && !(sigilOp.operand instanceof IdentifierNode)) {
                // Compile the glob name expression (e.g., $probe)
                bc.compileNode(sigilOp.operand, -1, RuntimeContextType.SCALAR);
                int nameScalarReg = bc.lastResultReg;

                // Load the glob using dynamic name
                int globReg = bc.allocateRegister();
                int pkgIdx = bc.addToStringPool(bc.getCurrentPackage());
                bc.emitWithToken(Opcodes.LOAD_GLOB_DYNAMIC, node.getIndex());
                bc.emitReg(globReg);
                bc.emitReg(nameScalarReg);
                bc.emit(pkgIdx);

                // Push the glob onto the local stack
                bc.emit(Opcodes.PUSH_LOCAL_VARIABLE);
                bc.emitReg(globReg);

                // Compile the RHS value
                bc.compileNode(node.right, -1, rhsContext);
                int valueReg = bc.lastResultReg;

                // Store value to glob
                bc.emit(Opcodes.STORE_GLOB);
                bc.emitReg(globReg);
                bc.emitReg(valueReg);

                bc.lastResultReg = globReg;
                return true;
            }
            if (sigil.equals("our") && sigilOp.operand instanceof OperatorNode innerSigilOp
                    && innerSigilOp.operand instanceof IdentifierNode idNode) {
                return handleLocalOurAssignment(bc, node, innerSigilOp, idNode, rhsContext);
            }
            // Handle: local $#array = value
            if (sigil.equals("$#")) {
                int arrayReg = resolveArrayForDollarHash(bc, sigilOp);
                // Save the array state so it's restored on scope exit
                bc.emit(Opcodes.PUSH_LOCAL_VARIABLE);
                bc.emitReg(arrayReg);
                // Compile the RHS value
                bc.compileNode(node.right, -1, rhsContext);
                int valueReg = bc.lastResultReg;
                // Set $#array to the new value
                bc.emit(Opcodes.SET_ARRAY_LAST_INDEX);
                bc.emitReg(arrayReg);
                bc.emitReg(valueReg);
                bc.lastResultReg = valueReg;
                return true;
            }
        }
        if (localOperand instanceof ListNode listNode) {
            return handleLocalListAssignment(bc, node, listNode, rhsContext);
        }
        return false;
    }

    private static boolean handleLocalOurAssignment(BytecodeCompiler bc, BinaryOperatorNode node,
            OperatorNode innerSigilOp, IdentifierNode idNode, int rhsContext) {
        String innerSigil = innerSigilOp.operator;
        String varName = innerSigil + idNode.name;
        String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, bc.getCurrentPackage());
        int nameIdx = bc.addToStringPool(globalVarName);
        int ourReg = bc.hasVariable(varName) ? bc.getVariableRegister(varName) : bc.addVariable(varName, "our");
        bc.compileNode(node.right, -1, rhsContext);
        int valueReg = bc.lastResultReg;
        int localReg = bc.allocateRegister();
        switch (innerSigil) {
            case "$" -> {
                bc.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                bc.emitReg(ourReg);
                bc.emit(nameIdx);
                bc.emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                bc.emitReg(localReg);
                bc.emit(nameIdx);
                bc.emit(Opcodes.SET_SCALAR);
                bc.emitReg(localReg);
                bc.emitReg(valueReg);
                bc.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                bc.emitReg(ourReg);
                bc.emit(nameIdx);
            }
            case "@" -> {
                bc.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                bc.emitReg(ourReg);
                bc.emit(nameIdx);
                bc.emitWithToken(Opcodes.LOCAL_ARRAY, node.getIndex());
                bc.emitReg(localReg);
                bc.emit(nameIdx);
                bc.emit(Opcodes.ARRAY_SET_FROM_LIST);
                bc.emitReg(localReg);
                bc.emitReg(valueReg);
            }
            case "%" -> {
                bc.emit(Opcodes.LOAD_GLOBAL_HASH);
                bc.emitReg(ourReg);
                bc.emit(nameIdx);
                bc.emitWithToken(Opcodes.LOCAL_HASH, node.getIndex());
                bc.emitReg(localReg);
                bc.emit(nameIdx);
                bc.emit(Opcodes.HASH_SET_FROM_LIST);
                bc.emitReg(localReg);
                bc.emitReg(valueReg);
            }
            default -> {
                bc.throwCompilerException("Unsupported variable type in local our: " + innerSigil);
                return true;
            }
        }
        bc.lastResultReg = localReg;
        return true;
    }

    private static boolean tryAddAssignOptimization(BytecodeCompiler bc, BinaryOperatorNode node, int rhsContext) {
        if (!(node.left instanceof OperatorNode leftOp) || !(node.right instanceof BinaryOperatorNode rightBin)) return false;
        if (!leftOp.operator.equals("$") || !(leftOp.operand instanceof IdentifierNode leftId)) return false;
        if (!rightBin.operator.equals("+") || !(rightBin.left instanceof OperatorNode rightLeftOp)) return false;
        if (!rightLeftOp.operator.equals("$") || !(rightLeftOp.operand instanceof IdentifierNode rightLeftId)) return false;
        String leftVarName = "$" + leftId.name;
        String rightLeftVarName = "$" + rightLeftId.name;
        boolean isCaptured = bc.capturedVarIndices != null && bc.capturedVarIndices.containsKey(leftVarName);
        if (!leftVarName.equals(rightLeftVarName) || !bc.hasVariable(leftVarName) || isCaptured) return false;
        int targetReg = bc.getVariableRegister(leftVarName);
        bc.compileNode(rightBin.right, -1, rhsContext);
        int rhsReg = bc.lastResultReg;
        bc.emit(Opcodes.ADD_ASSIGN);
        bc.emitReg(targetReg);
        bc.emitReg(rhsReg);
        bc.lastResultReg = targetReg;
        return true;
    }

    private static boolean handleLocalListAssignment(BytecodeCompiler bc, BinaryOperatorNode node,
            ListNode listNode, int rhsContext) {
        if (listNode.elements.size() == 1) {
            Node element = listNode.elements.get(0);
            if (element instanceof OperatorNode sigilOp && sigilOp.operator.equals("$")
                    && sigilOp.operand instanceof IdentifierNode idNode) {
                String varName = "$" + idNode.name;
                if (bc.hasVariable(varName)) {
                    bc.throwCompilerException("Can't localize lexical variable " + varName);
                    return true;
                }
                bc.compileNode(node.right, -1, rhsContext);
                int valueReg = bc.lastResultReg;
                String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalVarName);
                int localReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                bc.emitReg(localReg);
                bc.emit(nameIdx);
                bc.emit(Opcodes.SET_SCALAR);
                bc.emitReg(localReg);
                bc.emitReg(valueReg);
                bc.lastResultReg = localReg;
                return true;
            }
        }
        bc.compileNode(node.right, -1, rhsContext);
        int valueReg = bc.lastResultReg;
        for (int i = 0; i < listNode.elements.size(); i++) {
            Node element = listNode.elements.get(i);
            if (element instanceof OperatorNode sigilOp && sigilOp.operator.equals("$")
                    && sigilOp.operand instanceof IdentifierNode idNode) {
                String varName = "$" + idNode.name;
                if (bc.hasVariable(varName)) {
                    bc.throwCompilerException("Can't localize lexical variable " + varName);
                    return true;
                }
                String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalVarName);
                int localReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                bc.emitReg(localReg);
                bc.emit(nameIdx);
                int idxReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_INT);
                bc.emitReg(idxReg);
                bc.emit(i);
                int elemReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_GET);
                bc.emitReg(elemReg);
                bc.emitReg(valueReg);
                bc.emitReg(idxReg);
                bc.emit(Opcodes.SET_SCALAR);
                bc.emitReg(localReg);
                bc.emitReg(elemReg);
                if (i == 0) bc.lastResultReg = localReg;
            }
        }
        return true;
    }

    /**
     * Helper method to compile assignment operators (=).
     * Extracted from visit(BinaryOperatorNode) to reduce method size.
     * Handles all forms of assignment including my/our/local, scalars, arrays, hashes, and slices.
     */
    public static void compileAssignmentOperator(BytecodeCompiler bytecodeCompiler, BinaryOperatorNode node) {
        // Determine the calling context for the RHS based on LHS type
        // Use LValueVisitor to properly determine context for all LHS patterns
        // including $array[index], $hash{key}, etc.
        int rhsContext = LValueVisitor.getContext(node);
        if (rhsContext == RuntimeContextType.VOID) {
            // VOID means not a valid L-value, but we still compile it - default to LIST
            rhsContext = RuntimeContextType.LIST;
        }

        // Set the context for subroutine calls in RHS
        int outerContext = bytecodeCompiler.currentCallContext;

            // Special case: my $x = value
            if (node.left instanceof OperatorNode leftOp) {
                if (leftOp.operator.equals("my") || leftOp.operator.equals("state")) {
                    // Extract variable name from "my"/"state" operand
                    Node myOperand = leftOp.operand;

                    // Handle my $x (where $x is OperatorNode("$", IdentifierNode("x")))
                    if (myOperand instanceof OperatorNode sigilOp) {
                        if (sigilOp.operator.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                            String varName = "$" + ((IdentifierNode) sigilOp.operand).name;

                            Integer beginIdObj = RuntimeCode.evalBeginIds.get(sigilOp);
                            if (beginIdObj != null) {
                                int beginId = beginIdObj;
                                int nameIdx = bytecodeCompiler.addToStringPool(varName);
                                int reg = bytecodeCompiler.allocateRegister();

                                bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_SCALAR, node.getIndex());
                                bytecodeCompiler.emitReg(reg);
                                bytecodeCompiler.emit(nameIdx);
                                bytecodeCompiler.emit(beginId);

                                // Now register contains a reference to the persistent RuntimeScalar
                                // Store the initializer value INTO that RuntimeScalar
                                bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                                int valueReg = bytecodeCompiler.lastResultReg;

                                // Set the value in the persistent scalar using SET_SCALAR
                                // This calls .set() on the RuntimeScalar without overwriting the reference
                                bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                                bytecodeCompiler.emitReg(reg);
                                bytecodeCompiler.emitReg(valueReg);

                                bytecodeCompiler.registerVariable(varName, reg);
                                bytecodeCompiler.lastResultReg = reg;
                                return;
                            }

                            if (leftOp.operator.equals("state")) {
                                // State variable without BEGIN id: use conditional initialization
                                // STATE_INIT_SCALAR handles both retrieval (non-destructive) and
                                // conditional first-time initialization
                                int persistId = sigilOp.id;
                                int nameIdx = bytecodeCompiler.addToStringPool(varName);
                                int reg = bytecodeCompiler.allocateRegister();

                                // Compile RHS (value to conditionally assign)
                                bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                                int valueReg = bytecodeCompiler.lastResultReg;

                                // STATE_INIT_SCALAR: retrieves persistent variable and
                                // only assigns if not yet initialized
                                bytecodeCompiler.emitWithToken(Opcodes.STATE_INIT_SCALAR, node.getIndex());
                                bytecodeCompiler.emitReg(reg);
                                bytecodeCompiler.emitReg(valueReg);
                                bytecodeCompiler.emit(nameIdx);
                                bytecodeCompiler.emit(persistId);

                                bytecodeCompiler.registerVariable(varName, reg);

                                // Runtime attribute dispatch for state variables with attributes
                                bytecodeCompiler.emitVarAttrsIfNeeded(leftOp, reg, "$");

                                bytecodeCompiler.lastResultReg = reg;
                                return;
                            }

                            // Regular lexical variable (not captured)
                            // Compile RHS first, before adding variable to scope,
                            // so that `my $x = $x` reads the outer $x on the RHS
                            bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                            int valueReg = bytecodeCompiler.lastResultReg;

                            // Now allocate register for new lexical variable and add to symbol table
                            int reg = bytecodeCompiler.addVariable(varName, "my");

                            boolean hasAttrs = leftOp.annotations != null
                                    && leftOp.annotations.containsKey("attributes");
                            if (hasAttrs) {
                                // When attributes are present (e.g., my $x : TieLoop = $i),
                                // we must create the scalar first, dispatch attributes (which
                                // may tie the variable), then assign the value so STORE fires.
                                bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                                bytecodeCompiler.emitReg(reg);
                                bytecodeCompiler.emitVarAttrsIfNeeded(leftOp, reg, "$");
                                bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                                bytecodeCompiler.emitReg(reg);
                                bytecodeCompiler.emitReg(valueReg);
                            } else {
                                bytecodeCompiler.emit(Opcodes.MY_SCALAR);
                                bytecodeCompiler.emitReg(reg);
                                bytecodeCompiler.emitReg(valueReg);
                            }

                            bytecodeCompiler.lastResultReg = reg;
                            return;
                        } else if (sigilOp.operator.equals("@") && sigilOp.operand instanceof IdentifierNode) {
                            // Handle my @array = ...
                            String varName = "@" + ((IdentifierNode) sigilOp.operand).name;

                            Integer beginIdArr = RuntimeCode.evalBeginIds.get(sigilOp);
                            if (beginIdArr != null) {
                                int beginId = beginIdArr;
                                int nameIdx = bytecodeCompiler.addToStringPool(varName);
                                int arrayReg = bytecodeCompiler.allocateRegister();

                                bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                                bytecodeCompiler.emitReg(arrayReg);
                                bytecodeCompiler.emit(nameIdx);
                                bytecodeCompiler.emit(beginId);

                                // Compile RHS (should evaluate to a list)
                                bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                                int listReg = bytecodeCompiler.lastResultReg;

                                // Populate array from list
                                bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                                bytecodeCompiler.emitReg(arrayReg);
                                bytecodeCompiler.emitReg(listReg);

                                bytecodeCompiler.registerVariable(varName, arrayReg);

                                if (rhsContext == RuntimeContextType.SCALAR) {
                                    int countReg = bytecodeCompiler.allocateRegister();
                                    bytecodeCompiler.emit(Opcodes.ARRAY_SIZE);
                                    bytecodeCompiler.emitReg(countReg);
                                    bytecodeCompiler.emitReg(listReg);
                                    bytecodeCompiler.lastResultReg = countReg;
                                } else {
                                    bytecodeCompiler.lastResultReg = arrayReg;
                                }
                                return;
                            }

                            int arrayReg = bytecodeCompiler.allocateRegister();

                            bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                            int listReg = bytecodeCompiler.lastResultReg;

                            bytecodeCompiler.registerVariable(varName, arrayReg);
                            bytecodeCompiler.emit(Opcodes.NEW_ARRAY);
                            bytecodeCompiler.emitReg(arrayReg);

                            bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emitReg(listReg);

                            // Runtime attribute dispatch for my variables with attributes
                            bytecodeCompiler.emitVarAttrsIfNeeded(leftOp, arrayReg, "@");

                            if (rhsContext == RuntimeContextType.SCALAR) {
                                int countReg = bytecodeCompiler.allocateRegister();
                                bytecodeCompiler.emit(Opcodes.ARRAY_SIZE);
                                bytecodeCompiler.emitReg(countReg);
                                bytecodeCompiler.emitReg(listReg);
                                bytecodeCompiler.lastResultReg = countReg;
                            } else {
                                bytecodeCompiler.lastResultReg = arrayReg;
                            }
                            return;
                        } else if (sigilOp.operator.equals("%") && sigilOp.operand instanceof IdentifierNode) {
                            // Handle my %hash = ...
                            String varName = "%" + ((IdentifierNode) sigilOp.operand).name;

                            Integer beginIdHash = RuntimeCode.evalBeginIds.get(sigilOp);
                            if (beginIdHash != null) {
                                int beginId = beginIdHash;
                                int nameIdx = bytecodeCompiler.addToStringPool(varName);
                                int hashReg = bytecodeCompiler.allocateRegister();

                                bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                                bytecodeCompiler.emitReg(hashReg);
                                bytecodeCompiler.emit(nameIdx);
                                bytecodeCompiler.emit(beginId);

                                // Compile RHS (should evaluate to a list)
                                bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                                int listReg = bytecodeCompiler.lastResultReg;

                                // Populate hash from list
                                bytecodeCompiler.emit(Opcodes.HASH_SET_FROM_LIST);
                                bytecodeCompiler.emitReg(hashReg);
                                bytecodeCompiler.emitReg(listReg);

                                bytecodeCompiler.registerVariable(varName, hashReg);
                                bytecodeCompiler.lastResultReg = hashReg;
                                return;
                            }

                            // Regular lexical hash (not captured)
                            // Allocate register but don't add to scope yet,
                            // so that `my %h = %h` reads the outer %h on the RHS
                            int hashReg = bytecodeCompiler.allocateRegister();

                            // Compile RHS first, before adding variable to scope
                            bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                            int listReg = bytecodeCompiler.lastResultReg;

                            // Now add to symbol table and create hash
                            bytecodeCompiler.registerVariable(varName, hashReg);
                            bytecodeCompiler.emit(Opcodes.NEW_HASH);
                            bytecodeCompiler.emitReg(hashReg);

                            // Populate hash from list
                            bytecodeCompiler.emit(Opcodes.HASH_SET_FROM_LIST);
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emitReg(listReg);

                            // Runtime attribute dispatch for my variables with attributes
                            bytecodeCompiler.emitVarAttrsIfNeeded(leftOp, hashReg, "%");

                            bytecodeCompiler.lastResultReg = hashReg;
                            return;
                        }
                    }

                    // Handle my x (direct identifier without sigil)
                    if (myOperand instanceof IdentifierNode) {
                        String varName = ((IdentifierNode) myOperand).name;

                        // Compile RHS first, before adding variable to scope
                        bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                        int valueReg = bytecodeCompiler.lastResultReg;

                        // Now allocate register and add to symbol table
                        int reg = bytecodeCompiler.addVariable(varName, "my");

                        bytecodeCompiler.emit(Opcodes.MY_SCALAR);
                        bytecodeCompiler.emitReg(reg);
                        bytecodeCompiler.emitReg(valueReg);

                        bytecodeCompiler.lastResultReg = reg;
                        return;
                    }

                    // Handle my ($x, $y, @rest) = ... - list declaration with assignment
                    // Uses SET_FROM_LIST to match JVM backend's setFromList() semantics
                    if (myOperand instanceof ListNode listNode) {

                        // Compile RHS first
                        bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                        int listReg = bytecodeCompiler.lastResultReg;

                        // Convert to list if needed
                        int rhsListReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.SCALAR_TO_LIST);
                        bytecodeCompiler.emitReg(rhsListReg);
                        bytecodeCompiler.emitReg(listReg);

                        // Declare all variables and collect their registers
                        List<Integer> varRegs = new ArrayList<>();
                        for (int i = 0; i < listNode.elements.size(); i++) {
                            Node element = listNode.elements.get(i);
                            if (element instanceof OperatorNode sigilOp) {
                                String sigil = sigilOp.operator;

                                if (sigilOp.operand instanceof IdentifierNode) {
                                    String varName = sigil + ((IdentifierNode) sigilOp.operand).name;
                                    int varReg;

                                    Integer beginIdList = RuntimeCode.evalBeginIds.get(sigilOp);
                                    if (beginIdList != null) {
                                        int beginId = beginIdList;
                                        int nameIdx = bytecodeCompiler.addToStringPool(varName);
                                        varReg = bytecodeCompiler.allocateRegister();

                                        switch (sigil) {
                                            case "$" -> {
                                                bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_SCALAR, node.getIndex());
                                                bytecodeCompiler.emitReg(varReg);
                                                bytecodeCompiler.emit(nameIdx);
                                                bytecodeCompiler.emit(beginId);
                                            }
                                            case "@" -> {
                                                bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                                                bytecodeCompiler.emitReg(varReg);
                                                bytecodeCompiler.emit(nameIdx);
                                                bytecodeCompiler.emit(beginId);
                                            }
                                            case "%" -> {
                                                bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                                                bytecodeCompiler.emitReg(varReg);
                                                bytecodeCompiler.emit(nameIdx);
                                                bytecodeCompiler.emit(beginId);
                                            }
                                        }
                                        bytecodeCompiler.registerVariable(varName, varReg);
                                    } else {
                                        varReg = bytecodeCompiler.addVariable(varName, "my");
                                        switch (sigil) {
                                            case "$" -> {
                                                bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                                                bytecodeCompiler.emitReg(varReg);
                                            }
                                            case "@" -> {
                                                bytecodeCompiler.emit(Opcodes.NEW_ARRAY);
                                                bytecodeCompiler.emitReg(varReg);
                                            }
                                            case "%" -> {
                                                bytecodeCompiler.emit(Opcodes.NEW_HASH);
                                                bytecodeCompiler.emitReg(varReg);
                                            }
                                        }
                                    }
                                    varRegs.add(varReg);
                                }
                            }
                        }

                        // Build LHS list and assign via SET_FROM_LIST
                        int lhsListReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.CREATE_LIST);
                        bytecodeCompiler.emitReg(lhsListReg);
                        bytecodeCompiler.emit(varRegs.size());
                        for (int reg : varRegs) {
                            bytecodeCompiler.emitReg(reg);
                        }

                        int resultReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.SET_FROM_LIST);
                        bytecodeCompiler.emitReg(resultReg);
                        bytecodeCompiler.emitReg(lhsListReg);
                        bytecodeCompiler.emitReg(rhsListReg);

                        bytecodeCompiler.lastResultReg = resultReg;
                        return;
                    }
                }

                // Special case: local $x = value
                if (handleLocalAssignment(bytecodeCompiler, node, leftOp, rhsContext)) {
                    return;
                }
            }

            // Regular assignment: $x = value
            // OPTIMIZATION: Detect $x = $x + $y and emit ADD_ASSIGN instead of ADD_SCALAR + ALIAS
            if (tryAddAssignOptimization(bytecodeCompiler, node, rhsContext)) {
                return;
            }

            // Handle ${block} = value and $$var = value (symbolic references)
            // We need to evaluate the LHS FIRST to get the variable name,
            // then evaluate the RHS, to ensure the RHS doesn't clobber the LHS registers
            if (node.left instanceof OperatorNode leftOp && leftOp.operator.equals("$")) {
                boolean strictRefsEnabled = bytecodeCompiler.isStrictRefsEnabled();

                if (leftOp.operand instanceof BlockNode block) {
                    // ${block} = value — mirrors JVM EmitVariable.java case "$"
                    bytecodeCompiler.compileNode(block, -1, rhsContext);
                    int nameReg = bytecodeCompiler.lastResultReg;

                    // Deref to get lvalue target (strict or non-strict)
                    int derefReg = bytecodeCompiler.allocateRegister();
                    if (strictRefsEnabled) {
                        bytecodeCompiler.emitWithToken(Opcodes.DEREF_SCALAR_STRICT, node.getIndex());
                        bytecodeCompiler.emitReg(derefReg);
                        bytecodeCompiler.emitReg(nameReg);
                    } else {
                        int pkgIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());
                        bytecodeCompiler.emitWithToken(Opcodes.DEREF_SCALAR_NONSTRICT, node.getIndex());
                        bytecodeCompiler.emitReg(derefReg);
                        bytecodeCompiler.emitReg(nameReg);
                        bytecodeCompiler.emit(pkgIdx);
                    }

                    // Now compile the RHS and assign
                    bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                    int valueReg = bytecodeCompiler.lastResultReg;
                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                    bytecodeCompiler.emitReg(derefReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = valueReg;
                    return;
                } else if (leftOp.operand instanceof OperatorNode) {
                    // $$var = value — mirrors JVM EmitVariable.java case "$"
                    bytecodeCompiler.compileNode(leftOp.operand, -1, rhsContext);
                    int nameReg = bytecodeCompiler.lastResultReg;

                    int derefReg = bytecodeCompiler.allocateRegister();
                    if (strictRefsEnabled) {
                        bytecodeCompiler.emitWithToken(Opcodes.DEREF_SCALAR_STRICT, node.getIndex());
                        bytecodeCompiler.emitReg(derefReg);
                        bytecodeCompiler.emitReg(nameReg);
                    } else {
                        int pkgIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());
                        bytecodeCompiler.emitWithToken(Opcodes.DEREF_SCALAR_NONSTRICT, node.getIndex());
                        bytecodeCompiler.emitReg(derefReg);
                        bytecodeCompiler.emitReg(nameReg);
                        bytecodeCompiler.emit(pkgIdx);
                    }

                    bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                    int valueReg = bytecodeCompiler.lastResultReg;
                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                    bytecodeCompiler.emitReg(derefReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = valueReg;
                    return;
                }
            }

            // Regular assignment: $x = value (no optimization)
            // Compile RHS first
            bytecodeCompiler.compileNode(node.right, -1, rhsContext);
            int valueReg = bytecodeCompiler.lastResultReg;

            // Assign to LHS
            if (node.left instanceof OperatorNode leftOp) {
                if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode) {
                    String varName = "$" + ((IdentifierNode) leftOp.operand).name;

                    if (bytecodeCompiler.hasVariable(varName)) {
                        // Lexical variable - check if it's captured
                        int targetReg = bytecodeCompiler.getVariableRegister(varName);

                        if ((bytecodeCompiler.capturedVarIndices != null && bytecodeCompiler.capturedVarIndices.containsKey(varName))
                                || bytecodeCompiler.closureCapturedVarNames.contains(varName)) {
                            // Captured variable - use SET_SCALAR to preserve aliasing.
                            // LOAD_UNDEF would replace the register with a new RuntimeScalar,
                            // breaking the shared reference that closures depend on.
                            bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(valueReg);
                        } else {
                            // Regular lexical - create a fresh RuntimeScalar, then copy the value into it.
                            // LOAD_UNDEF allocates a new mutable RuntimeScalar in the target register;
                            // SET_SCALAR copies the source value into it.
                            // This avoids two bugs:
                            //   - ALIAS shares constants from the pool, corrupting them on later mutation
                            //   - SET_SCALAR alone modifies the existing object in-place, which breaks
                            //     'local' variable restoration when the register was shared
                            bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(valueReg);
                        }

                        bytecodeCompiler.lastResultReg = targetReg;
                    } else {
                        // Global variable
                        // Check strict vars before assignment
                        if (bytecodeCompiler.shouldBlockGlobalUnderStrictVars(varName)) {
                            bytecodeCompiler.throwCompilerException("Global symbol \"" + varName + "\" requires explicit package name");
                        }

                        // Strip sigil and normalize name (e.g., "$x" → "main::x")
                        String bareVarName = varName.substring(1);  // Remove sigil
                        String normalizedName = NameNormalizer.normalizeVariableName(bareVarName, bytecodeCompiler.getCurrentPackage());
                        int nameIdx = bytecodeCompiler.addToStringPool(normalizedName);
                        bytecodeCompiler.emit(Opcodes.STORE_GLOBAL_SCALAR);
                        bytecodeCompiler.emit(nameIdx);
                        bytecodeCompiler.emitReg(valueReg);
                        // Return the global lvalue so ($_ = "x") =~ s/// modifies $_ in-place
                        int lvalReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                        bytecodeCompiler.emitReg(lvalReg);
                        bytecodeCompiler.emit(nameIdx);
                        bytecodeCompiler.lastResultReg = lvalReg;
                    }
                } else if (leftOp.operator.equals("@") && leftOp.operand instanceof IdentifierNode) {
                    // Array assignment: @array = ...
                    String varName = "@" + ((IdentifierNode) leftOp.operand).name;

                    int arrayReg;
                    if (bytecodeCompiler.currentSubroutineBeginId != 0 && bytecodeCompiler.currentSubroutineClosureVars != null
                            && bytecodeCompiler.currentSubroutineClosureVars.contains(varName)) {
                        arrayReg = bytecodeCompiler.allocateRegister();
                        int nameIdx = bytecodeCompiler.addToStringPool(varName);
                        bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emit(nameIdx);
                        bytecodeCompiler.emit(bytecodeCompiler.currentSubroutineBeginId);
                    } else if (bytecodeCompiler.hasVariable(varName)) {
                        arrayReg = bytecodeCompiler.getVariableRegister(varName);
                    } else {
                        arrayReg = bytecodeCompiler.allocateRegister();
                        String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) leftOp.operand).name, bytecodeCompiler.getCurrentPackage());
                        int nameIdx = bytecodeCompiler.addToStringPool(globalArrayName);
                        bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emit(nameIdx);
                    }

                    // Populate array from list using setFromList
                    bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                    bytecodeCompiler.emitReg(arrayReg);
                    bytecodeCompiler.emitReg(valueReg);

                    // In scalar context, return the array size; in list context, return the array
                    if (outerContext == RuntimeContextType.SCALAR) {
                        // Convert array to scalar (returns size)
                        int sizeReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.ARRAY_SIZE);
                        bytecodeCompiler.emitReg(sizeReg);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.lastResultReg = sizeReg;
                    } else {
                        bytecodeCompiler.lastResultReg = arrayReg;
                    }
                } else if (leftOp.operator.equals("%") && leftOp.operand instanceof IdentifierNode) {
                    // Hash assignment: %hash = ...
                    String varName = "%" + ((IdentifierNode) leftOp.operand).name;

                    int hashReg;
                    if (bytecodeCompiler.currentSubroutineBeginId != 0 && bytecodeCompiler.currentSubroutineClosureVars != null
                            && bytecodeCompiler.currentSubroutineClosureVars.contains(varName)) {
                        hashReg = bytecodeCompiler.allocateRegister();
                        int nameIdx = bytecodeCompiler.addToStringPool(varName);
                        bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                        bytecodeCompiler.emitReg(hashReg);
                        bytecodeCompiler.emit(nameIdx);
                        bytecodeCompiler.emit(bytecodeCompiler.currentSubroutineBeginId);
                    } else if (bytecodeCompiler.hasVariable(varName)) {
                        hashReg = bytecodeCompiler.getVariableRegister(varName);
                    } else {
                        hashReg = bytecodeCompiler.allocateRegister();
                        String globalHashName = NameNormalizer.normalizeVariableName(((IdentifierNode) leftOp.operand).name, bytecodeCompiler.getCurrentPackage());
                        int nameIdx = bytecodeCompiler.addToStringPool(globalHashName);
                        bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_HASH);
                        bytecodeCompiler.emitReg(hashReg);
                        bytecodeCompiler.emit(nameIdx);
                    }

                    // In scalar context, count RHS elements BEFORE hash assignment
                    // (the assignment may modify the RHS if it's the same hash)
                    int countReg = -1;
                    if (outerContext == RuntimeContextType.SCALAR) {
                        countReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.LIST_TO_COUNT);
                        bytecodeCompiler.emitReg(countReg);
                        bytecodeCompiler.emitReg(valueReg);
                    }

                    // Populate hash from list using setFromList
                    bytecodeCompiler.emit(Opcodes.HASH_SET_FROM_LIST);
                    bytecodeCompiler.emitReg(hashReg);
                    bytecodeCompiler.emitReg(valueReg);

                    // Return the pre-computed count or the hash
                    if (outerContext == RuntimeContextType.SCALAR) {
                        bytecodeCompiler.lastResultReg = countReg;
                    } else {
                        bytecodeCompiler.lastResultReg = hashReg;
                    }
                } else if (leftOp.operator.equals("our")) {
                    // Assignment to our variable: our $x = value or our @x = value or our %x = value
                    // Compile the our declaration first (which loads the global into a register)
                    bytecodeCompiler.compileNode(leftOp, -1, rhsContext);
                    int targetReg = bytecodeCompiler.lastResultReg;

                    // Now assign the RHS value to the target register
                    // The target register contains either a scalar, array, or hash
                    // We need to determine which and use the appropriate assignment

                    // Extract the sigil from our operand
                    if (leftOp.operand instanceof OperatorNode sigilOp) {
                        String sigil = sigilOp.operator;

                        if (sigil.equals("$")) {
                            // Scalar: use SET_SCALAR to modify value without breaking alias
                            bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(valueReg);
                        } else if (sigil.equals("@")) {
                            // Array: use ARRAY_SET_FROM_LIST
                            bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(valueReg);
                        } else if (sigil.equals("%")) {
                            // Hash: use HASH_SET_FROM_LIST
                            bytecodeCompiler.emit(Opcodes.HASH_SET_FROM_LIST);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(valueReg);
                        }
                    } else if (leftOp.operand instanceof ListNode listNode) {
                        // our ($a, $b) = ... - list declaration with assignment
                        // Uses SET_FROM_LIST to match JVM backend's setFromList() semantics

                        // Convert RHS to list
                        int rhsListReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.SCALAR_TO_LIST);
                        bytecodeCompiler.emitReg(rhsListReg);
                        bytecodeCompiler.emitReg(valueReg);

                        // Collect variable registers (already declared by our visitor)
                        List<Integer> varRegs = new ArrayList<>();
                        for (int i = 0; i < listNode.elements.size(); i++) {
                            Node element = listNode.elements.get(i);
                            if (element instanceof OperatorNode sigilOp) {
                                String sigil = sigilOp.operator;
                                if (sigilOp.operand instanceof IdentifierNode) {
                                    String varName = sigil + ((IdentifierNode) sigilOp.operand).name;
                                    varRegs.add(bytecodeCompiler.getVariableRegister(varName));
                                }
                            }
                        }

                        // Build LHS list and assign via SET_FROM_LIST
                        int lhsListReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.CREATE_LIST);
                        bytecodeCompiler.emitReg(lhsListReg);
                        bytecodeCompiler.emit(varRegs.size());
                        for (int reg : varRegs) {
                            bytecodeCompiler.emitReg(reg);
                        }

                        int resultReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.SET_FROM_LIST);
                        bytecodeCompiler.emitReg(resultReg);
                        bytecodeCompiler.emitReg(lhsListReg);
                        bytecodeCompiler.emitReg(rhsListReg);

                        bytecodeCompiler.lastResultReg = resultReg;
                        
                        return;
                    }

                    bytecodeCompiler.lastResultReg = targetReg;
                } else if (leftOp.operator.equals("*") && leftOp.operand instanceof IdentifierNode) {
                    // Typeglob assignment: *foo = value
                    String varName = ((IdentifierNode) leftOp.operand).name;
                    String globalName = NameNormalizer.normalizeVariableName(varName, bytecodeCompiler.getCurrentPackage());
                    int nameIdx = bytecodeCompiler.addToStringPool(globalName);

                    // Load the glob
                    int globReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.LOAD_GLOB);
                    bytecodeCompiler.emitReg(globReg);
                    bytecodeCompiler.emit(nameIdx);

                    // Store value to glob
                    bytecodeCompiler.emit(Opcodes.STORE_GLOB);
                    bytecodeCompiler.emitReg(globReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = globReg;
                } else if (leftOp.operator.equals("*") && leftOp.operand instanceof BlockNode) {
                    // Symbolic typeglob assignment: *{"name"} = value (no strict refs)
                    // Evaluate the block to get the glob name as a scalar, then load glob by name
                    bytecodeCompiler.compileNode(leftOp.operand, -1, rhsContext);
                    int nameScalarReg = bytecodeCompiler.lastResultReg;

                    int globReg = bytecodeCompiler.allocateRegister();
                    int pkgIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());
                    bytecodeCompiler.emitWithToken(Opcodes.LOAD_GLOB_DYNAMIC, node.getIndex());
                    bytecodeCompiler.emitReg(globReg);
                    bytecodeCompiler.emitReg(nameScalarReg);
                    bytecodeCompiler.emit(pkgIdx);

                    // Store value to glob
                    bytecodeCompiler.emit(Opcodes.STORE_GLOB);
                    bytecodeCompiler.emitReg(globReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = globReg;
                } else if (leftOp.operator.equals("*")) {
                    // Glob assignment where the glob comes from an expression, e.g. $ref->** = ...
                    // or 'name'->** = ...
                    // Compile the glob expression to obtain the RuntimeGlob, then store through it.
                    bytecodeCompiler.compileNode(leftOp, -1, rhsContext);
                    int globReg = bytecodeCompiler.lastResultReg;

                    bytecodeCompiler.emit(Opcodes.STORE_GLOB);
                    bytecodeCompiler.emitReg(globReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = globReg;
                } else if (leftOp.operator.equals("pos")) {
                    // pos($var) = value - lvalue assignment to regex position
                    // pos() returns a PosLvalueScalar that can be assigned to
                    bytecodeCompiler.compileNode(node.left, -1, rhsContext);
                    int lvalueReg = bytecodeCompiler.lastResultReg;

                    // Use SET_SCALAR to assign through the lvalue
                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                    bytecodeCompiler.emitReg(lvalueReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = valueReg;
                } else if (leftOp.operator.equals("substr")) {
                    bytecodeCompiler.compileNode(node.left, -1, rhsContext);
                    int lvalueReg = bytecodeCompiler.lastResultReg;

                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                    bytecodeCompiler.emitReg(lvalueReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = valueReg;
                } else if (leftOp.operator.equals("vec")) {
                    // vec($x, offset, bits) = value - lvalue assignment to bit vector
                    // vec() returns a RuntimeVecLvalue that can be assigned to
                    bytecodeCompiler.compileNode(node.left, -1, rhsContext);
                    int lvalueReg = bytecodeCompiler.lastResultReg;

                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                    bytecodeCompiler.emitReg(lvalueReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = valueReg;
                } else if (leftOp.operator.equals("@") && (leftOp.operand instanceof OperatorNode || leftOp.operand instanceof BlockNode)) {
                    // Array dereference assignment: @$r = ... or @{expr} = ...
                    // The operand should evaluate to an array reference

                    boolean isSimpleScalarDeref = leftOp.operand instanceof OperatorNode derefOp && derefOp.operator.equals("$");

                    if (isSimpleScalarDeref || leftOp.operand instanceof BlockNode) {
                        // Compile the operand to get the array reference
                        bytecodeCompiler.compileNode(leftOp.operand, -1, RuntimeContextType.SCALAR);
                        int scalarRefReg = bytecodeCompiler.lastResultReg;

                        // Dereference to get the actual array
                        int arrayReg = bytecodeCompiler.allocateRegister();
                        if (bytecodeCompiler.isStrictRefsEnabled()) {
                            bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emitReg(scalarRefReg);
                        } else {
                            int pkgIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());
                            bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, node.getIndex());
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emitReg(scalarRefReg);
                            bytecodeCompiler.emit(pkgIdx);
                        }

                        // Assign the value to the dereferenced array
                        bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emitReg(valueReg);

                        // In scalar context, return array size; in list context, return the array
                        if (outerContext == RuntimeContextType.SCALAR) {
                            int sizeReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emit(Opcodes.ARRAY_SIZE);
                            bytecodeCompiler.emitReg(sizeReg);
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.lastResultReg = sizeReg;
                        } else {
                            bytecodeCompiler.lastResultReg = arrayReg;
                        }
                    } else {
                        bytecodeCompiler.throwCompilerException("Assignment to unsupported array dereference");
                    }
                } else if (leftOp.operator.equals("$#")) {
                    int arrayReg = CompileAssignment.resolveArrayForDollarHash(bytecodeCompiler, leftOp);
                    bytecodeCompiler.emit(Opcodes.SET_ARRAY_LAST_INDEX);
                    bytecodeCompiler.emitReg(arrayReg);
                    bytecodeCompiler.emitReg(valueReg);
                    bytecodeCompiler.lastResultReg = valueReg;
                } else if (leftOp.operator.equals("%") && (leftOp.operand instanceof OperatorNode || leftOp.operand instanceof BlockNode)) {
                    // Hash dereference assignment: %$r = ... or %{expr} = ...
                    // The operand should evaluate to a hash reference

                    boolean isSimpleScalarDeref = leftOp.operand instanceof OperatorNode derefOp && derefOp.operator.equals("$");

                    if (isSimpleScalarDeref || leftOp.operand instanceof BlockNode) {
                        // Compile the operand to get the hash reference
                        bytecodeCompiler.compileNode(leftOp.operand, -1, RuntimeContextType.SCALAR);
                        int scalarRefReg = bytecodeCompiler.lastResultReg;

                        // Dereference to get the actual hash
                        int hashReg = bytecodeCompiler.allocateRegister();
                        if (bytecodeCompiler.isStrictRefsEnabled()) {
                            bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emitReg(scalarRefReg);
                        } else {
                            int pkgIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());
                            bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH_NONSTRICT, node.getIndex());
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emitReg(scalarRefReg);
                            bytecodeCompiler.emit(pkgIdx);
                        }

                        // Assign the value to the dereferenced hash
                        bytecodeCompiler.emit(Opcodes.HASH_SET_FROM_LIST);
                        bytecodeCompiler.emitReg(hashReg);
                        bytecodeCompiler.emitReg(valueReg);

                        // In list context, return the hash flattened; in other contexts return the hash
                        bytecodeCompiler.lastResultReg = hashReg;
                    } else {
                        bytecodeCompiler.throwCompilerException("Assignment to unsupported hash dereference");
                    }
                } else {
                    if (leftOp.operator.equals("chop") || leftOp.operator.equals("chomp")) {
                        bytecodeCompiler.throwCompilerException("Can't modify " + leftOp.operator + " in scalar assignment");
                    }
                    bytecodeCompiler.throwCompilerException("Assignment to unsupported operator: " + leftOp.operator);
                }
            } else if (node.left instanceof IdentifierNode) {
                String varName = ((IdentifierNode) node.left).name;

                if (bytecodeCompiler.hasVariable(varName)) {
                    int targetReg = bytecodeCompiler.getVariableRegister(varName);
                    String varNameWithSigil = varName.startsWith("$") ? varName : "$" + varName;
                    if ((bytecodeCompiler.capturedVarIndices != null && bytecodeCompiler.capturedVarIndices.containsKey(varNameWithSigil))
                            || bytecodeCompiler.closureCapturedVarNames.contains(varNameWithSigil)
                            || bytecodeCompiler.closureCapturedVarNames.contains(varName)) {
                        bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                        bytecodeCompiler.emitReg(targetReg);
                        bytecodeCompiler.emitReg(valueReg);
                    } else {
                        bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                        bytecodeCompiler.emitReg(targetReg);
                        bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                        bytecodeCompiler.emitReg(targetReg);
                        bytecodeCompiler.emitReg(valueReg);
                    }
                    bytecodeCompiler.lastResultReg = targetReg;
                } else {
                    // Global variable (varName has no sigil here)
                    // Check strict vars - add sigil for checking
                    String varNameWithSigil = "$" + varName;
                    if (bytecodeCompiler.shouldBlockGlobalUnderStrictVars(varNameWithSigil)) {
                        bytecodeCompiler.throwCompilerException("Global symbol \"" + varNameWithSigil + "\" requires explicit package name");
                    }

                    String normalizedName = NameNormalizer.normalizeVariableName(varName, bytecodeCompiler.getCurrentPackage());
                    int nameIdx = bytecodeCompiler.addToStringPool(normalizedName);
                    bytecodeCompiler.emit(Opcodes.STORE_GLOBAL_SCALAR);
                    bytecodeCompiler.emit(nameIdx);
                    bytecodeCompiler.emitReg(valueReg);
                    // Return the global variable lvalue (not the rhs copy) so that
                    // ($_ = "x") =~ s/// can modify $_ in-place via the lvalue.
                    int lvalueReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                    bytecodeCompiler.emitReg(lvalueReg);
                    bytecodeCompiler.emit(nameIdx);
                    bytecodeCompiler.lastResultReg = lvalueReg;
                }
            } else if (node.left instanceof BinaryOperatorNode leftBin) {

                // Handle array slice assignment: @array[1, 3, 5] = (20, 30, 40)
                if (leftBin.operator.equals("[") && leftBin.left instanceof OperatorNode arrayOp) {

                    // Must be @array (not $array)
                    if (arrayOp.operator.equals("@") && arrayOp.operand instanceof IdentifierNode) {
                        String varName = "@" + ((IdentifierNode) arrayOp.operand).name;

                        int arrayReg;
                        if (bytecodeCompiler.currentSubroutineBeginId != 0 && bytecodeCompiler.currentSubroutineClosureVars != null
                                && bytecodeCompiler.currentSubroutineClosureVars.contains(varName)) {
                            arrayReg = bytecodeCompiler.allocateRegister();
                            int nameIdx = bytecodeCompiler.addToStringPool(varName);
                            bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emit(nameIdx);
                            bytecodeCompiler.emit(bytecodeCompiler.currentSubroutineBeginId);
                        } else if (bytecodeCompiler.hasVariable(varName)) {
                            arrayReg = bytecodeCompiler.getVariableRegister(varName);
                        } else {
                            arrayReg = bytecodeCompiler.allocateRegister();
                            String globalArrayName = NameNormalizer.normalizeVariableName(
                                    ((IdentifierNode) arrayOp.operand).name,
                                    bytecodeCompiler.getCurrentPackage()
                            );
                            int nameIdx = bytecodeCompiler.addToStringPool(globalArrayName);
                            bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emit(nameIdx);
                        }

                        // Compile indices (right side of [])
                        // ArrayLiteralNode contains the indices
                        if (!(leftBin.right instanceof ArrayLiteralNode)) {
                            bytecodeCompiler.throwCompilerException("Array slice assignment requires index list");
                        }

                        ArrayLiteralNode indicesNode = (ArrayLiteralNode) leftBin.right;
                        List<Integer> indexRegs = new ArrayList<>();
                        for (Node indexNode : indicesNode.elements) {
                            bytecodeCompiler.compileNode(indexNode, -1, rhsContext);
                            indexRegs.add(bytecodeCompiler.lastResultReg);
                        }

                        // Create indices list
                        int indicesReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.CREATE_LIST);
                        bytecodeCompiler.emitReg(indicesReg);
                        bytecodeCompiler.emit(indexRegs.size());
                        for (int indexReg : indexRegs) {
                            bytecodeCompiler.emitReg(indexReg);
                        }

                        // Emit direct opcode ARRAY_SLICE_SET (use valueReg from line 729)
                        bytecodeCompiler.emit(Opcodes.ARRAY_SLICE_SET);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emitReg(indicesReg);
                        bytecodeCompiler.emitReg(valueReg);

                        bytecodeCompiler.lastResultReg = arrayReg;
                        
                        return;
                    }
                }

                // Handle single element array assignment
                // For: $array[index] = value or $matrix[3][0] = value
                if (leftBin.operator.equals("[")) {
                    int arrayReg;

                    // Check if left side is a variable or multidimensional access
                    if (leftBin.left instanceof OperatorNode arrayOp) {

                        // Single element assignment: $array[index] = value
                        if (arrayOp.operator.equals("$") && arrayOp.operand instanceof IdentifierNode) {
                            String varName = ((IdentifierNode) arrayOp.operand).name;
                            String arrayVarName = "@" + varName;

                            if (bytecodeCompiler.currentSubroutineBeginId != 0 && bytecodeCompiler.currentSubroutineClosureVars != null
                                    && bytecodeCompiler.currentSubroutineClosureVars.contains(arrayVarName)) {
                                arrayReg = bytecodeCompiler.allocateRegister();
                                int nameIdx = bytecodeCompiler.addToStringPool(arrayVarName);
                                bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                                bytecodeCompiler.emitReg(arrayReg);
                                bytecodeCompiler.emit(nameIdx);
                                bytecodeCompiler.emit(bytecodeCompiler.currentSubroutineBeginId);
                            } else if (bytecodeCompiler.hasVariable(arrayVarName)) {
                                arrayReg = bytecodeCompiler.getVariableRegister(arrayVarName);
                            } else {
                                arrayReg = bytecodeCompiler.allocateRegister();
                                String globalArrayName = NameNormalizer.normalizeVariableName(
                                        varName,
                                        bytecodeCompiler.getCurrentPackage()
                                );
                                int nameIdx = bytecodeCompiler.addToStringPool(globalArrayName);
                                bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                                bytecodeCompiler.emitReg(arrayReg);
                                bytecodeCompiler.emit(nameIdx);
                            }
                        } else {
                            bytecodeCompiler.throwCompilerException("Assignment requires scalar dereference: $var[index]");
                            return;
                        }
                    } else if (leftBin.left instanceof BinaryOperatorNode) {
                        // Multidimensional case: $matrix[3][0] = value
                        // Compile left side (which returns a scalar containing an array reference)
                        bytecodeCompiler.compileNode(leftBin.left, -1, rhsContext);
                        int scalarReg = bytecodeCompiler.lastResultReg;

                        // Dereference the array reference to get the actual array
                        arrayReg = bytecodeCompiler.allocateRegister();
                        if (bytecodeCompiler.isStrictRefsEnabled()) {
                            bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emitReg(scalarReg);
                        } else {
                            int pkgIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());
                            bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, node.getIndex());
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emitReg(scalarReg);
                            bytecodeCompiler.emit(pkgIdx);
                        }
                    } else {
                        bytecodeCompiler.throwCompilerException("Array assignment requires variable or expression on left side");
                        return;
                    }

                    // Compile index expression
                    if (!(leftBin.right instanceof ArrayLiteralNode)) {
                        bytecodeCompiler.throwCompilerException("Array assignment requires ArrayLiteralNode on right side");
                    }
                    ArrayLiteralNode indexNode = (ArrayLiteralNode) leftBin.right;
                    if (indexNode.elements.isEmpty()) {
                        bytecodeCompiler.throwCompilerException("Array assignment requires index expression");
                    }

                    bytecodeCompiler.compileNode(indexNode.elements.get(0), -1, rhsContext);
                    int indexReg = bytecodeCompiler.lastResultReg;

                    // Emit ARRAY_SET which returns the lvalue (element) in rd
                    // This is critical for operations like: ($a[0] = $val) =~ s/pattern//
                    int resultReg = bytecodeCompiler.allocateOutputRegister();
                    bytecodeCompiler.emit(Opcodes.ARRAY_SET);
                    bytecodeCompiler.emitReg(resultReg);
                    bytecodeCompiler.emitReg(arrayReg);
                    bytecodeCompiler.emitReg(indexReg);
                    bytecodeCompiler.emitReg(valueReg);
                    bytecodeCompiler.lastResultReg = resultReg;
                    
                    return;
                } else if (leftBin.operator.equals("{")) {
                    // Hash element/slice assignment
                    // $hash{key} = value (scalar element)
                    // @hash{keys} = values (slice)

                    // 1. Get hash variable (leftBin.left)
                    int hashReg;
                    if (leftBin.left instanceof OperatorNode hashOp) {

                        // Check for hash slice assignment: @hash{keys} = values
                        if (hashOp.operator.equals("@")) {
                            if (hashOp.operand instanceof IdentifierNode idNode) {
                                String varName = idNode.name;
                                String hashVarName = "%" + varName;

                                if (bytecodeCompiler.currentSubroutineBeginId != 0 && bytecodeCompiler.currentSubroutineClosureVars != null
                                        && bytecodeCompiler.currentSubroutineClosureVars.contains(hashVarName)) {
                                    hashReg = bytecodeCompiler.allocateRegister();
                                    int nameIdx = bytecodeCompiler.addToStringPool(hashVarName);
                                    bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                                    bytecodeCompiler.emitReg(hashReg);
                                    bytecodeCompiler.emit(nameIdx);
                                    bytecodeCompiler.emit(bytecodeCompiler.currentSubroutineBeginId);
                                } else if (bytecodeCompiler.hasVariable(hashVarName)) {
                                    hashReg = bytecodeCompiler.getVariableRegister(hashVarName);
                                } else {
                                    hashReg = bytecodeCompiler.allocateRegister();
                                    String globalHashName = NameNormalizer.normalizeVariableName(
                                            varName,
                                            bytecodeCompiler.getCurrentPackage()
                                    );
                                    int nameIdx = bytecodeCompiler.addToStringPool(globalHashName);
                                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_HASH);
                                    bytecodeCompiler.emitReg(hashReg);
                                    bytecodeCompiler.emit(nameIdx);
                                }
                            } else if (hashOp.operand instanceof OperatorNode) {
                                bytecodeCompiler.compileNode(hashOp.operand, -1, rhsContext);
                                int scalarRefReg = bytecodeCompiler.lastResultReg;
                                hashReg = bytecodeCompiler.allocateRegister();
                                if (bytecodeCompiler.isStrictRefsEnabled()) {
                                    bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                                    bytecodeCompiler.emitReg(hashReg);
                                    bytecodeCompiler.emitReg(scalarRefReg);
                                } else {
                                    int pkgIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());
                                    bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH_NONSTRICT, node.getIndex());
                                    bytecodeCompiler.emitReg(hashReg);
                                    bytecodeCompiler.emitReg(scalarRefReg);
                                    bytecodeCompiler.emit(pkgIdx);
                                }
                            } else {
                                bytecodeCompiler.throwCompilerException("Hash slice assignment requires identifier or reference");
                                return;
                            }

                            // Get the keys from HashLiteralNode
                            if (!(leftBin.right instanceof HashLiteralNode keysNode)) {
                                bytecodeCompiler.throwCompilerException("Hash slice assignment requires HashLiteralNode");
                                return;
                            }
                            if (keysNode.elements.isEmpty()) {
                                bytecodeCompiler.throwCompilerException("Hash slice assignment requires at least one key");
                                return;
                            }

                            // Compile all keys into a list
                            List<Integer> keyRegs = new ArrayList<>();
                            for (Node keyElement : keysNode.elements) {
                                if (keyElement instanceof IdentifierNode) {
                                    // Bareword key - autoquote
                                    String keyString = ((IdentifierNode) keyElement).name;
                                    int keyReg = bytecodeCompiler.allocateRegister();
                                    int keyIdx = bytecodeCompiler.addToStringPool(keyString);
                                    bytecodeCompiler.emit(Opcodes.LOAD_STRING);
                                    bytecodeCompiler.emitReg(keyReg);
                                    bytecodeCompiler.emit(keyIdx);
                                    keyRegs.add(keyReg);
                                } else {
                                    // Expression key - use default context to allow arrays to expand
                                    keyElement.accept(bytecodeCompiler);
                                    keyRegs.add(bytecodeCompiler.lastResultReg);
                                }
                            }

                            // Create a RuntimeList from key registers
                            int keysListReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emit(Opcodes.CREATE_LIST);
                            bytecodeCompiler.emitReg(keysListReg);
                            bytecodeCompiler.emit(keyRegs.size());
                            for (int keyReg : keyRegs) {
                                bytecodeCompiler.emitReg(keyReg);
                            }

                            // Emit direct opcode HASH_SLICE_SET (use valueReg from line 729)
                            bytecodeCompiler.emit(Opcodes.HASH_SLICE_SET);
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emitReg(keysListReg);
                            bytecodeCompiler.emitReg(valueReg);

                            bytecodeCompiler.lastResultReg = valueReg;
                            
                            return;
                        } else if (hashOp.operator.equals("$")) {
                            // $hash{key} or $$ref{key} - dereference to get hash
                            if (hashOp.operand instanceof IdentifierNode) {
                                String varName = ((IdentifierNode) hashOp.operand).name;
                                String hashVarName = "%" + varName;

                                if (bytecodeCompiler.currentSubroutineBeginId != 0 && bytecodeCompiler.currentSubroutineClosureVars != null
                                        && bytecodeCompiler.currentSubroutineClosureVars.contains(hashVarName)) {
                                    hashReg = bytecodeCompiler.allocateRegister();
                                    int nameIdx = bytecodeCompiler.addToStringPool(hashVarName);
                                    bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                                    bytecodeCompiler.emitReg(hashReg);
                                    bytecodeCompiler.emit(nameIdx);
                                    bytecodeCompiler.emit(bytecodeCompiler.currentSubroutineBeginId);
                                } else if (bytecodeCompiler.hasVariable(hashVarName)) {
                                    hashReg = bytecodeCompiler.getVariableRegister(hashVarName);
                                } else {
                                    hashReg = bytecodeCompiler.allocateRegister();
                                    String globalHashName = NameNormalizer.normalizeVariableName(
                                            varName,
                                            bytecodeCompiler.getCurrentPackage()
                                    );
                                    int nameIdx = bytecodeCompiler.addToStringPool(globalHashName);
                                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_HASH);
                                    bytecodeCompiler.emitReg(hashReg);
                                    bytecodeCompiler.emit(nameIdx);
                                }
                            } else {
                                // $$ref{key} = value — compile the scalar ref expression and deref to hash
                                bytecodeCompiler.compileNode(hashOp.operand, -1, rhsContext);
                                int scalarReg = bytecodeCompiler.lastResultReg;
                                hashReg = bytecodeCompiler.allocateRegister();
                                if (bytecodeCompiler.isStrictRefsEnabled()) {
                                    bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                                    bytecodeCompiler.emitReg(hashReg);
                                    bytecodeCompiler.emitReg(scalarReg);
                                } else {
                                    int pkgIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());
                                    bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH_NONSTRICT, node.getIndex());
                                    bytecodeCompiler.emitReg(hashReg);
                                    bytecodeCompiler.emitReg(scalarReg);
                                    bytecodeCompiler.emit(pkgIdx);
                                }
                            }
                        } else {
                            bytecodeCompiler.throwCompilerException("Hash assignment requires scalar dereference: $var{key}");
                            return;
                        }
                    } else if (leftBin.left instanceof BinaryOperatorNode) {
                        // Nested: $hash{outer}{inner} = value
                        // Compile left side (returns scalar containing hash reference or autovivifies)
                        bytecodeCompiler.compileNode(leftBin.left, -1, rhsContext);
                        int scalarReg = bytecodeCompiler.lastResultReg;

                        // Dereference to get the hash (with autovivification)
                        hashReg = bytecodeCompiler.allocateRegister();
                        if (bytecodeCompiler.isStrictRefsEnabled()) {
                            bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emitReg(scalarReg);
                        } else {
                            int pkgIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());
                            bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH_NONSTRICT, node.getIndex());
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emitReg(scalarReg);
                            bytecodeCompiler.emit(pkgIdx);
                        }
                    } else {
                        bytecodeCompiler.throwCompilerException("Hash assignment requires variable or expression on left side");
                        return;
                    }

                    // 2. Compile key expression
                    if (!(leftBin.right instanceof HashLiteralNode keyNode)) {
                        bytecodeCompiler.throwCompilerException("Hash assignment requires HashLiteralNode on right side");
                        return;
                    }
                    if (keyNode.elements.isEmpty()) {
                        bytecodeCompiler.throwCompilerException("Hash key required for assignment");
                        return;
                    }

                    // Compile the key
                    // Special case: IdentifierNode in hash access is autoquoted (bareword key)
                    int keyReg;
                    Node keyElement = keyNode.elements.get(0);
                    if (keyElement instanceof IdentifierNode) {
                        // Bareword key: $hash{key} -> key is autoquoted to "key"
                        String keyString = ((IdentifierNode) keyElement).name;
                        keyReg = bytecodeCompiler.allocateRegister();
                        int keyIdx = bytecodeCompiler.addToStringPool(keyString);
                        bytecodeCompiler.emit(Opcodes.LOAD_STRING);
                        bytecodeCompiler.emitReg(keyReg);
                        bytecodeCompiler.emit(keyIdx);
                    } else {
                        // Expression key: $hash{$var} or $hash{func()} - must be compiled in SCALAR context
                        bytecodeCompiler.compileNode(keyElement, -1, RuntimeContextType.SCALAR);
                        keyReg = bytecodeCompiler.lastResultReg;
                    }

                    // 3. Emit HASH_SET which returns the lvalue (element) in rd
                    // This is critical for operations like: ($h{key} = $val) =~ s/pattern//
                    int resultReg = bytecodeCompiler.allocateOutputRegister();
                    bytecodeCompiler.emit(Opcodes.HASH_SET);
                    bytecodeCompiler.emitReg(resultReg);
                    bytecodeCompiler.emitReg(hashReg);
                    bytecodeCompiler.emitReg(keyReg);
                    bytecodeCompiler.emitReg(valueReg);
                    bytecodeCompiler.lastResultReg = resultReg;
                    
                    return;
                }

                // Handle arrow dereference assignment: $ref->{key} = value or $$ref{key} = value
                // These parse as BinaryOperatorNode("->", expr, HashLiteralNode/ArrayLiteralNode)
                if (leftBin.operator.equals("->")) {
                    Node rightSide = leftBin.right;
                    if (rightSide instanceof HashLiteralNode hashKey) {
                        // $ref->{key} = value — hash element via reference
                        bytecodeCompiler.compileNode(leftBin.left, -1, rhsContext);
                        int refReg = bytecodeCompiler.lastResultReg;

                        // Dereference to get the hash
                        int hashReg = bytecodeCompiler.allocateRegister();
                        if (bytecodeCompiler.isStrictRefsEnabled()) {
                            bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emitReg(refReg);
                        } else {
                            int pkgIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());
                            bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH_NONSTRICT, node.getIndex());
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emitReg(refReg);
                            bytecodeCompiler.emit(pkgIdx);
                        }

                        // Compile key
                        int keyReg;
                        if (!hashKey.elements.isEmpty()) {
                            Node keyElement = hashKey.elements.get(0);
                            if (keyElement instanceof IdentifierNode) {
                                String keyString = ((IdentifierNode) keyElement).name;
                                keyReg = bytecodeCompiler.allocateRegister();
                                int keyIdx = bytecodeCompiler.addToStringPool(keyString);
                                bytecodeCompiler.emit(Opcodes.LOAD_STRING);
                                bytecodeCompiler.emitReg(keyReg);
                                bytecodeCompiler.emit(keyIdx);
                            } else {
                                // Expression key - must be compiled in SCALAR context
                                bytecodeCompiler.compileNode(keyElement, -1, RuntimeContextType.SCALAR);
                                keyReg = bytecodeCompiler.lastResultReg;
                            }
                        } else {
                            bytecodeCompiler.throwCompilerException("Hash key required for arrow assignment");
                            return;
                        }

                        // Emit HASH_SET which returns the lvalue (element) in rd
                        int resultReg = bytecodeCompiler.allocateOutputRegister();
                        bytecodeCompiler.emit(Opcodes.HASH_SET);
                        bytecodeCompiler.emitReg(resultReg);
                        bytecodeCompiler.emitReg(hashReg);
                        bytecodeCompiler.emitReg(keyReg);
                        bytecodeCompiler.emitReg(valueReg);
                        bytecodeCompiler.lastResultReg = resultReg;
                        
                        return;
                    } else if (rightSide instanceof ArrayLiteralNode arrayIdx) {
                        // $ref->[index] = value — array element via reference
                        bytecodeCompiler.compileNode(leftBin.left, -1, rhsContext);
                        int refReg = bytecodeCompiler.lastResultReg;

                        // Dereference to get the array
                        int arrayReg = bytecodeCompiler.allocateRegister();
                        if (bytecodeCompiler.isStrictRefsEnabled()) {
                            bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emitReg(refReg);
                        } else {
                            int pkgIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());
                            bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, node.getIndex());
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emitReg(refReg);
                            bytecodeCompiler.emit(pkgIdx);
                        }

                        // Compile index
                        if (arrayIdx.elements.isEmpty()) {
                            bytecodeCompiler.throwCompilerException("Array index required for arrow assignment");
                            return;
                        }
                        bytecodeCompiler.compileNode(arrayIdx.elements.get(0), -1, rhsContext);
                        int idxReg = bytecodeCompiler.lastResultReg;

                        // Emit ARRAY_SET which returns the lvalue (element) in rd
                        int resultReg = bytecodeCompiler.allocateOutputRegister();
                        bytecodeCompiler.emit(Opcodes.ARRAY_SET);
                        bytecodeCompiler.emitReg(resultReg);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emitReg(idxReg);
                        bytecodeCompiler.emitReg(valueReg);
                        bytecodeCompiler.lastResultReg = resultReg;
                        
                        return;
                    }
                }

                // Handle lvalue subroutine: f() = value
                // When a function is called in lvalue context, it returns a RuntimeBaseProxy
                // that wraps a mutable reference. We can assign to it using SET_SCALAR.
                if (leftBin.operator.equals("(")) {
                    // Call the function (which returns a RuntimeBaseProxy in lvalue context)
                    bytecodeCompiler.compileNode(node.left, -1, rhsContext);
                    int lvalueReg = bytecodeCompiler.lastResultReg;

                    // Compile RHS
                    bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                    int rhsReg = bytecodeCompiler.lastResultReg;

                    // Assign to the lvalue using SET_SCALAR
                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                    bytecodeCompiler.emitReg(lvalueReg);
                    bytecodeCompiler.emitReg(rhsReg);

                    bytecodeCompiler.lastResultReg = rhsReg;
                    
                    return;
                }

                // Handle constant-folded logical operators: e.g. `1 && my $x = val` → `my $x = val`
                // Perl constant-folds logical ops with constant LHS at compile time.
                if (leftBin.operator.equals("&&") || leftBin.operator.equals("and") ||
                        leftBin.operator.equals("||") || leftBin.operator.equals("or") ||
                        leftBin.operator.equals("//")) {
                    Node foldedLeft = ConstantFoldingVisitor.foldConstants(node.left);
                    if (foldedLeft != node.left) {
                        // Operator was folded - recursively handle assignment with folded LHS
                        BinaryOperatorNode newNode = new BinaryOperatorNode("=", foldedLeft, node.right, node.tokenIndex);
                        compileAssignmentOperator(bytecodeCompiler, newNode);
                        return;
                    }
                }

                bytecodeCompiler.throwCompilerException("Assignment to non-identifier not yet supported: " + node.left.getClass().getSimpleName());
            } else if (node.left instanceof TernaryOperatorNode) {
                LValueVisitor.getContext(node.left);
                bytecodeCompiler.compileNode(node.left, -1, rhsContext);
                int lvalueReg = bytecodeCompiler.lastResultReg;
                bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                int rhsReg = bytecodeCompiler.lastResultReg;
                bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                bytecodeCompiler.emitReg(lvalueReg);
                bytecodeCompiler.emitReg(rhsReg);
                bytecodeCompiler.lastResultReg = rhsReg;
                
            } else if (node.left instanceof ListNode listNode) {
                // List assignment: ($a, $b) = ... or () = ...
                // Follow the JVM backend approach: compile LHS as a list of lvalues,
                // then call setFromList() on it.
                // In scalar context, returns the number of elements on RHS
                // In list context, returns the RHS list
                LValueVisitor.getContext(node.left);

                // RHS was already compiled at the "regular assignment" fallthrough above (valueReg).
                // Reuse it instead of compiling again.
                int rhsReg = valueReg;

                // Convert RHS to RuntimeList if needed
                int rhsListReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.SCALAR_TO_LIST);
                bytecodeCompiler.emitReg(rhsListReg);
                bytecodeCompiler.emitReg(rhsReg);

                // Compile LHS ListNode in LIST context - this produces a RuntimeList of lvalues
                // This follows the JVM backend approach (EmitVariable.java line 837)
                bytecodeCompiler.compileNode(listNode, -1, RuntimeContextType.LIST);
                int lhsListReg = bytecodeCompiler.lastResultReg;

                // Call SET_FROM_LIST to assign RHS values to LHS lvalues
                // setFromList() returns a RuntimeArray with scalarContextSize set to the
                // original RHS element count, and elements containing the assigned values.
                // Note: rhsListReg is consumed (elements cleared) by setFromList's addToArray.
                int resultReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.SET_FROM_LIST);
                bytecodeCompiler.emitReg(resultReg);
                bytecodeCompiler.emitReg(lhsListReg);
                bytecodeCompiler.emitReg(rhsListReg);

                if (outerContext == RuntimeContextType.SCALAR) {
                    // In scalar context, return the RHS element count.
                    // resultReg is a RuntimeArray with scalarContextSize set;
                    // ARRAY_SIZE on it calls scalar() which returns scalarContextSize.
                    int countReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.ARRAY_SIZE);
                    bytecodeCompiler.emitReg(countReg);
                    bytecodeCompiler.emitReg(resultReg);
                    bytecodeCompiler.lastResultReg = countReg;
                } else {
                    // In list context, return the assigned values (after hash dedup etc.)
                    bytecodeCompiler.lastResultReg = resultReg;
                }

            } else {
                bytecodeCompiler.throwCompilerException("Assignment to non-identifier not yet supported: " + node.left.getClass().getSimpleName());
            }
    }

    static int resolveArrayForDollarHash(BytecodeCompiler bytecodeCompiler, OperatorNode dollarHashOp) {
        if (dollarHashOp.operand instanceof OperatorNode operandOp
                && operandOp.operator.equals("@") && operandOp.operand instanceof IdentifierNode idNode) {
            String varName = "@" + idNode.name;
            if (bytecodeCompiler.currentSubroutineBeginId != 0 && bytecodeCompiler.currentSubroutineClosureVars != null
                    && bytecodeCompiler.currentSubroutineClosureVars.contains(varName)) {
                int arrayReg = bytecodeCompiler.allocateRegister();
                int nameIdx = bytecodeCompiler.addToStringPool(varName);
                bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, dollarHashOp.getIndex());
                bytecodeCompiler.emitReg(arrayReg);
                bytecodeCompiler.emit(nameIdx);
                bytecodeCompiler.emit(bytecodeCompiler.currentSubroutineBeginId);
                return arrayReg;
            }
            if (bytecodeCompiler.hasVariable(varName)) {
                return bytecodeCompiler.getVariableRegister(varName);
            }
            int arrayReg = bytecodeCompiler.allocateRegister();
            String globalName = NameNormalizer.normalizeVariableName(idNode.name, bytecodeCompiler.getCurrentPackage());
            int nameIdx = bytecodeCompiler.addToStringPool(globalName);
            bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
            bytecodeCompiler.emitReg(arrayReg);
            bytecodeCompiler.emit(nameIdx);
            return arrayReg;
        } else if (dollarHashOp.operand instanceof IdentifierNode idNode) {
            String varName = "@" + idNode.name;
            if (bytecodeCompiler.currentSubroutineBeginId != 0 && bytecodeCompiler.currentSubroutineClosureVars != null
                    && bytecodeCompiler.currentSubroutineClosureVars.contains(varName)) {
                int arrayReg = bytecodeCompiler.allocateRegister();
                int nameIdx = bytecodeCompiler.addToStringPool(varName);
                bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, dollarHashOp.getIndex());
                bytecodeCompiler.emitReg(arrayReg);
                bytecodeCompiler.emit(nameIdx);
                bytecodeCompiler.emit(bytecodeCompiler.currentSubroutineBeginId);
                return arrayReg;
            }
            if (bytecodeCompiler.hasVariable(varName)) {
                return bytecodeCompiler.getVariableRegister(varName);
            }
            int arrayReg = bytecodeCompiler.allocateRegister();
            String globalName = NameNormalizer.normalizeVariableName(idNode.name, bytecodeCompiler.getCurrentPackage());
            int nameIdx = bytecodeCompiler.addToStringPool(globalName);
            bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
            bytecodeCompiler.emitReg(arrayReg);
            bytecodeCompiler.emit(nameIdx);
            return arrayReg;
        } else if (dollarHashOp.operand instanceof OperatorNode operandOp && operandOp.operator.equals("$")) {
            operandOp.accept(bytecodeCompiler);
            int refReg = bytecodeCompiler.lastResultReg;
            int arrayReg = bytecodeCompiler.allocateRegister();
            if (bytecodeCompiler.isStrictRefsEnabled()) {
                bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY, dollarHashOp.getIndex());
                bytecodeCompiler.emitReg(arrayReg);
                bytecodeCompiler.emitReg(refReg);
            } else {
                int pkgIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());
                bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, dollarHashOp.getIndex());
                bytecodeCompiler.emitReg(arrayReg);
                bytecodeCompiler.emitReg(refReg);
                bytecodeCompiler.emit(pkgIdx);
            }
            return arrayReg;
        } else if (dollarHashOp.operand instanceof BlockNode blockNode) {
            // $#{BLOCK} = value - evaluate block to get array reference
            int savedContext = bytecodeCompiler.currentCallContext;
            bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
            blockNode.accept(bytecodeCompiler);
            bytecodeCompiler.currentCallContext = savedContext;
            int refReg = bytecodeCompiler.lastResultReg;
            int arrayReg = bytecodeCompiler.allocateRegister();
            if (bytecodeCompiler.isStrictRefsEnabled()) {
                bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY, dollarHashOp.getIndex());
                bytecodeCompiler.emitReg(arrayReg);
                bytecodeCompiler.emitReg(refReg);
            } else {
                int pkgIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());
                bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, dollarHashOp.getIndex());
                bytecodeCompiler.emitReg(arrayReg);
                bytecodeCompiler.emitReg(refReg);
                bytecodeCompiler.emit(pkgIdx);
            }
            return arrayReg;
        }
        bytecodeCompiler.throwCompilerException("$# assignment requires array variable");
        return -1;
    }
}

