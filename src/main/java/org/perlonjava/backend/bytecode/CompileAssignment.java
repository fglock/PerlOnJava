package org.perlonjava.backend.bytecode;

import org.perlonjava.frontend.analysis.LValueVisitor;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.util.ArrayList;
import java.util.List;

public class CompileAssignment {
    /**
     * Helper method to compile assignment operators (=).
     * Extracted from visit(BinaryOperatorNode) to reduce method size.
     * Handles all forms of assignment including my/our/local, scalars, arrays, hashes, and slices.
     */
    public static void compileAssignmentOperator(BytecodeCompiler bytecodeCompiler, BinaryOperatorNode node) {
        // Determine the calling context for the RHS based on LHS type
        int rhsContext = RuntimeContextType.LIST; // Default

        // Check if LHS is a scalar assignment (my $x = ... or our $x = ...)
        if (node.left instanceof OperatorNode) {
            OperatorNode leftOp = (OperatorNode) node.left;
            if ((leftOp.operator.equals("my") || leftOp.operator.equals("state") || leftOp.operator.equals("our")) && leftOp.operand instanceof OperatorNode) {
                OperatorNode sigilOp = (OperatorNode) leftOp.operand;
                if (sigilOp.operator.equals("$")) {
                    // Scalar assignment: use SCALAR context for RHS
                    rhsContext = RuntimeContextType.SCALAR;
                }
            } else if (leftOp.operator.equals("$")) {
                rhsContext = RuntimeContextType.SCALAR;
            } else if (leftOp.operator.equals("*")) {
                rhsContext = RuntimeContextType.SCALAR;
            }
        }

        // Set the context for subroutine calls in RHS
        int savedContext = bytecodeCompiler.currentCallContext;
        try {
            bytecodeCompiler.currentCallContext = rhsContext;

            // Special case: my $x = value
            if (node.left instanceof OperatorNode) {
                OperatorNode leftOp = (OperatorNode) node.left;
                if (leftOp.operator.equals("my") || leftOp.operator.equals("state")) {
                    // Extract variable name from "my"/"state" operand
                    Node myOperand = leftOp.operand;

                    // Handle my $x (where $x is OperatorNode("$", IdentifierNode("x")))
                    if (myOperand instanceof OperatorNode) {
                        OperatorNode sigilOp = (OperatorNode) myOperand;
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
                                node.right.accept(bytecodeCompiler);
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

                            // Regular lexical variable (not captured)
                            // Compile RHS first, before adding variable to scope,
                            // so that `my $x = $x` reads the outer $x on the RHS
                            node.right.accept(bytecodeCompiler);
                            int valueReg = bytecodeCompiler.lastResultReg;

                            // Now allocate register for new lexical variable and add to symbol table
                            int reg = bytecodeCompiler.addVariable(varName, "my");

                            bytecodeCompiler.emit(Opcodes.MY_SCALAR);
                            bytecodeCompiler.emitReg(reg);
                            bytecodeCompiler.emitReg(valueReg);

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
                                node.right.accept(bytecodeCompiler);
                                int listReg = bytecodeCompiler.lastResultReg;

                                // Populate array from list
                                bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                                bytecodeCompiler.emitReg(arrayReg);
                                bytecodeCompiler.emitReg(listReg);

                                bytecodeCompiler.registerVariable(varName, arrayReg);

                                // In scalar context, return the count of elements assigned
                                // In list/void context, return the array
                                if (bytecodeCompiler.currentCallContext == RuntimeContextType.SCALAR) {
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

                            // Regular lexical array (not captured)
                            // Allocate register but don't add to scope yet,
                            // so that `my @a = @a` reads the outer @a on the RHS
                            int arrayReg = bytecodeCompiler.allocateRegister();

                            // Compile RHS first, before adding variable to scope
                            node.right.accept(bytecodeCompiler);
                            int listReg = bytecodeCompiler.lastResultReg;

                            // Now add to symbol table and create array
                            bytecodeCompiler.registerVariable(varName, arrayReg);
                            bytecodeCompiler.emit(Opcodes.NEW_ARRAY);
                            bytecodeCompiler.emitReg(arrayReg);

                            // Populate array from list using setFromList
                            bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emitReg(listReg);

                            // In scalar context, return the count of elements assigned
                            // In list/void context, return the array
                            if (bytecodeCompiler.currentCallContext == RuntimeContextType.SCALAR) {
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
                                node.right.accept(bytecodeCompiler);
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
                            node.right.accept(bytecodeCompiler);
                            int listReg = bytecodeCompiler.lastResultReg;

                            // Now add to symbol table and create hash
                            bytecodeCompiler.registerVariable(varName, hashReg);
                            bytecodeCompiler.emit(Opcodes.NEW_HASH);
                            bytecodeCompiler.emitReg(hashReg);

                            // Populate hash from list
                            bytecodeCompiler.emit(Opcodes.HASH_SET_FROM_LIST);
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emitReg(listReg);

                            bytecodeCompiler.lastResultReg = hashReg;
                            return;
                        }
                    }

                    // Handle my x (direct identifier without sigil)
                    if (myOperand instanceof IdentifierNode) {
                        String varName = ((IdentifierNode) myOperand).name;

                        // Compile RHS first, before adding variable to scope
                        node.right.accept(bytecodeCompiler);
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
                    if (myOperand instanceof ListNode) {
                        ListNode listNode = (ListNode) myOperand;

                        // Compile RHS first
                        node.right.accept(bytecodeCompiler);
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
                if (leftOp.operator.equals("local")) {
                    // Extract variable from "local" operand
                    Node localOperand = leftOp.operand;

                    // Handle local $hash{key} = value (localizing hash element)
                    if (localOperand instanceof BinaryOperatorNode) {
                        BinaryOperatorNode hashAccess = (BinaryOperatorNode) localOperand;
                        if (hashAccess.operator.equals("{")) {
                            // Compile the hash access to get the hash element reference
                            // This returns a RuntimeScalar that is aliased to the hash slot
                            hashAccess.accept(bytecodeCompiler);
                            int elemReg = bytecodeCompiler.lastResultReg;

                            // Push this hash element to the local variable stack
                            bytecodeCompiler.emit(Opcodes.PUSH_LOCAL_VARIABLE);
                            bytecodeCompiler.emitReg(elemReg);

                            // Compile RHS
                            node.right.accept(bytecodeCompiler);
                            int valueReg = bytecodeCompiler.lastResultReg;

                            // Assign value to the hash element (which is already localized)
                            bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                            bytecodeCompiler.emitReg(elemReg);
                            bytecodeCompiler.emitReg(valueReg);

                            bytecodeCompiler.lastResultReg = elemReg;
                            return;
                        }
                    }

                    // Handle local $x (where $x is OperatorNode("$", IdentifierNode("x")))
                    if (localOperand instanceof OperatorNode) {
                        OperatorNode sigilOp = (OperatorNode) localOperand;
                        if (sigilOp.operator.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                            String varName = "$" + ((IdentifierNode) sigilOp.operand).name;

                            if (bytecodeCompiler.hasVariable(varName) && !bytecodeCompiler.isOurVariable(varName)) {
                                bytecodeCompiler.throwCompilerException("Can't localize lexical variable " + varName);
                                return;
                            }

                            // Compile RHS first
                            node.right.accept(bytecodeCompiler);
                            int valueReg = bytecodeCompiler.lastResultReg;

                            // It's a global variable - call makeLocal which returns the localized scalar
                            String packageName = bytecodeCompiler.getCurrentPackage();
                            String globalVarName = packageName + "::" + ((IdentifierNode) sigilOp.operand).name;
                            int nameIdx = bytecodeCompiler.addToStringPool(globalVarName);

                            int localReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                            bytecodeCompiler.emitReg(localReg);
                            bytecodeCompiler.emit(nameIdx);

                            // Assign value to the localized scalar (not to the global!)
                            bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                            bytecodeCompiler.emitReg(localReg);
                            bytecodeCompiler.emitReg(valueReg);

                            bytecodeCompiler.lastResultReg = localReg;
                            return;
                        } else if (sigilOp.operator.equals("@") && sigilOp.operand instanceof IdentifierNode) {
                            // Handle local @array = value
                            String varName = "@" + ((IdentifierNode) sigilOp.operand).name;

                            if (bytecodeCompiler.hasVariable(varName) && !bytecodeCompiler.isOurVariable(varName)) {
                                bytecodeCompiler.throwCompilerException("Can't localize lexical variable " + varName);
                                return;
                            }

                            // Compile RHS first
                            node.right.accept(bytecodeCompiler);
                            int valueReg = bytecodeCompiler.lastResultReg;

                            // It's a global array - get it and push to local stack
                            String packageName = bytecodeCompiler.getCurrentPackage();
                            String globalVarName = packageName + "::" + ((IdentifierNode) sigilOp.operand).name;
                            int nameIdx = bytecodeCompiler.addToStringPool(globalVarName);

                            int arrayReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emit(nameIdx);

                            // Push to local variable stack
                            bytecodeCompiler.emit(Opcodes.PUSH_LOCAL_VARIABLE);
                            bytecodeCompiler.emitReg(arrayReg);

                            // Populate array from list
                            bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emitReg(valueReg);

                            bytecodeCompiler.lastResultReg = arrayReg;
                            return;
                        } else if (sigilOp.operator.equals("%") && sigilOp.operand instanceof IdentifierNode) {
                            // Handle local %hash = value
                            String varName = "%" + ((IdentifierNode) sigilOp.operand).name;

                            if (bytecodeCompiler.hasVariable(varName) && !bytecodeCompiler.isOurVariable(varName)) {
                                bytecodeCompiler.throwCompilerException("Can't localize lexical variable " + varName);
                                return;
                            }

                            // Compile RHS first
                            node.right.accept(bytecodeCompiler);
                            int valueReg = bytecodeCompiler.lastResultReg;

                            // It's a global hash - get it and push to local stack
                            String packageName = bytecodeCompiler.getCurrentPackage();
                            String globalVarName = packageName + "::" + ((IdentifierNode) sigilOp.operand).name;
                            int nameIdx = bytecodeCompiler.addToStringPool(globalVarName);

                            int hashReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_HASH);
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emit(nameIdx);

                            // Push to local variable stack
                            bytecodeCompiler.emit(Opcodes.PUSH_LOCAL_VARIABLE);
                            bytecodeCompiler.emitReg(hashReg);

                            // Populate hash from list
                            bytecodeCompiler.emit(Opcodes.HASH_SET_FROM_LIST);
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emitReg(valueReg);

                            bytecodeCompiler.lastResultReg = hashReg;
                            return;
                        } else if (sigilOp.operator.equals("*") && sigilOp.operand instanceof IdentifierNode) {
                            // Handle local *glob = value
                            node.right.accept(bytecodeCompiler);
                            int valueReg = bytecodeCompiler.lastResultReg;

                            String globalName = NameNormalizer.normalizeVariableName(
                                    ((IdentifierNode) sigilOp.operand).name,
                                    bytecodeCompiler.getCurrentPackage());
                            int nameIdx = bytecodeCompiler.addToStringPool(globalName);

                            int globReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emitWithToken(Opcodes.LOCAL_GLOB, node.getIndex());
                            bytecodeCompiler.emitReg(globReg);
                            bytecodeCompiler.emit(nameIdx);

                            bytecodeCompiler.emit(Opcodes.STORE_GLOB);
                            bytecodeCompiler.emitReg(globReg);
                            bytecodeCompiler.emitReg(valueReg);

                            bytecodeCompiler.lastResultReg = globReg;
                            return;
                        } else if (sigilOp.operator.equals("our") && sigilOp.operand instanceof OperatorNode innerSigilOp
                                && innerSigilOp.operand instanceof IdentifierNode idNode) {
                            String innerSigil = innerSigilOp.operator;
                            String varName = innerSigil + idNode.name;
                            String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, bytecodeCompiler.getCurrentPackage());
                            int nameIdx = bytecodeCompiler.addToStringPool(globalVarName);

                            int ourReg = bytecodeCompiler.hasVariable(varName) ? bytecodeCompiler.getVariableRegister(varName) : bytecodeCompiler.addVariable(varName, "our");

                            node.right.accept(bytecodeCompiler);
                            int valueReg = bytecodeCompiler.lastResultReg;

                            switch (innerSigil) {
                                case "$" -> {
                                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                                    bytecodeCompiler.emitReg(ourReg);
                                    bytecodeCompiler.emit(nameIdx);
                                    int localReg = bytecodeCompiler.allocateRegister();
                                    bytecodeCompiler.emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                                    bytecodeCompiler.emitReg(localReg);
                                    bytecodeCompiler.emit(nameIdx);
                                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                                    bytecodeCompiler.emitReg(localReg);
                                    bytecodeCompiler.emitReg(valueReg);
                                    // After localization, reload ourReg so subsequent accesses
                                    // to the `our` variable see the new localized scalar.
                                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                                    bytecodeCompiler.emitReg(ourReg);
                                    bytecodeCompiler.emit(nameIdx);
                                    bytecodeCompiler.lastResultReg = localReg;
                                }
                                case "@" -> {
                                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                                    bytecodeCompiler.emitReg(ourReg);
                                    bytecodeCompiler.emit(nameIdx);
                                    int localReg = bytecodeCompiler.allocateRegister();
                                    bytecodeCompiler.emitWithToken(Opcodes.LOCAL_ARRAY, node.getIndex());
                                    bytecodeCompiler.emitReg(localReg);
                                    bytecodeCompiler.emit(nameIdx);
                                    bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                                    bytecodeCompiler.emitReg(localReg);
                                    bytecodeCompiler.emitReg(valueReg);
                                    bytecodeCompiler.lastResultReg = localReg;
                                }
                                case "%" -> {
                                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_HASH);
                                    bytecodeCompiler.emitReg(ourReg);
                                    bytecodeCompiler.emit(nameIdx);
                                    int localReg = bytecodeCompiler.allocateRegister();
                                    bytecodeCompiler.emitWithToken(Opcodes.LOCAL_HASH, node.getIndex());
                                    bytecodeCompiler.emitReg(localReg);
                                    bytecodeCompiler.emit(nameIdx);
                                    bytecodeCompiler.emit(Opcodes.HASH_SET_FROM_LIST);
                                    bytecodeCompiler.emitReg(localReg);
                                    bytecodeCompiler.emitReg(valueReg);
                                    bytecodeCompiler.lastResultReg = localReg;
                                }
                                default ->
                                        bytecodeCompiler.throwCompilerException("Unsupported variable type in local our: " + innerSigil);
                            }
                            return;
                        }
                    } else if (localOperand instanceof ListNode) {
                        // Handle local($x) = value or local($x, $y) = (v1, v2)
                        ListNode listNode = (ListNode) localOperand;

                        // Special case: single element list local($x) = value
                        if (listNode.elements.size() == 1) {
                            Node element = listNode.elements.get(0);
                            if (element instanceof OperatorNode) {
                                OperatorNode sigilOp = (OperatorNode) element;
                                if (sigilOp.operator.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                                    String varName = "$" + ((IdentifierNode) sigilOp.operand).name;

                                    // Check if it's a lexical variable (should not be localized)
                                    if (bytecodeCompiler.hasVariable(varName)) {
                                        bytecodeCompiler.throwCompilerException("Can't localize lexical variable " + varName);
                                        return;
                                    }

                                    // Compile RHS first
                                    node.right.accept(bytecodeCompiler);
                                    int valueReg = bytecodeCompiler.lastResultReg;

                                    // Get the global variable and localize it
                                    String packageName = bytecodeCompiler.getCurrentPackage();
                                    String globalVarName = packageName + "::" + ((IdentifierNode) sigilOp.operand).name;
                                    int nameIdx = bytecodeCompiler.addToStringPool(globalVarName);

                                    int localReg = bytecodeCompiler.allocateRegister();
                                    bytecodeCompiler.emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                                    bytecodeCompiler.emitReg(localReg);
                                    bytecodeCompiler.emit(nameIdx);

                                    // Assign value to the localized variable
                                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                                    bytecodeCompiler.emitReg(localReg);
                                    bytecodeCompiler.emitReg(valueReg);

                                    bytecodeCompiler.lastResultReg = localReg;
                                    return;
                                }
                            }
                        }

                        // Multi-element case: local($x, $y) = (v1, v2)
                        // Compile RHS first
                        node.right.accept(bytecodeCompiler);
                        int valueReg = bytecodeCompiler.lastResultReg;

                        // For each element in the list, localize and assign
                        for (int i = 0; i < listNode.elements.size(); i++) {
                            Node element = listNode.elements.get(i);

                            if (element instanceof OperatorNode) {
                                OperatorNode sigilOp = (OperatorNode) element;
                                if (sigilOp.operator.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                                    String varName = "$" + ((IdentifierNode) sigilOp.operand).name;

                                    // Check if it's a lexical variable (should not be localized)
                                    if (bytecodeCompiler.hasVariable(varName)) {
                                        bytecodeCompiler.throwCompilerException("Can't localize lexical variable " + varName);
                                        return;
                                    }

                                    // Get the global variable
                                    String packageName = bytecodeCompiler.getCurrentPackage();
                                    String globalVarName = packageName + "::" + ((IdentifierNode) sigilOp.operand).name;
                                    int nameIdx = bytecodeCompiler.addToStringPool(globalVarName);

                                    int localReg = bytecodeCompiler.allocateRegister();
                                    bytecodeCompiler.emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                                    bytecodeCompiler.emitReg(localReg);
                                    bytecodeCompiler.emit(nameIdx);

                                    // Extract element from RHS list
                                    int elemReg = bytecodeCompiler.allocateRegister();
                                    bytecodeCompiler.emit(Opcodes.ARRAY_GET);
                                    bytecodeCompiler.emitReg(elemReg);
                                    bytecodeCompiler.emitReg(valueReg);
                                    bytecodeCompiler.emitInt(i);

                                    // Assign to the localized variable
                                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                                    bytecodeCompiler.emitReg(localReg);
                                    bytecodeCompiler.emitReg(elemReg);

                                    if (i == 0) {
                                        // Return the first localized variable
                                        bytecodeCompiler.lastResultReg = localReg;
                                    }
                                }
                            }
                        }
                        return;
                    }
                }
            }

            // Regular assignment: $x = value
            // OPTIMIZATION: Detect $x = $x + $y and emit ADD_ASSIGN instead of ADD_SCALAR + ALIAS
            if (node.left instanceof OperatorNode && node.right instanceof BinaryOperatorNode) {
                OperatorNode leftOp = (OperatorNode) node.left;
                BinaryOperatorNode rightBin = (BinaryOperatorNode) node.right;

                if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode &&
                        rightBin.operator.equals("+") &&
                        rightBin.left instanceof OperatorNode) {

                    String leftVarName = "$" + ((IdentifierNode) leftOp.operand).name;
                    OperatorNode rightLeftOp = (OperatorNode) rightBin.left;

                    if (rightLeftOp.operator.equals("$") && rightLeftOp.operand instanceof IdentifierNode) {
                        String rightLeftVarName = "$" + ((IdentifierNode) rightLeftOp.operand).name;

                        // Pattern match: $x = $x + $y (emit ADD_ASSIGN)
                        // Skip optimization for captured variables (need SET_SCALAR)
                        boolean isCaptured = bytecodeCompiler.capturedVarIndices != null &&
                                bytecodeCompiler.capturedVarIndices.containsKey(leftVarName);

                        if (leftVarName.equals(rightLeftVarName) && bytecodeCompiler.hasVariable(leftVarName) && !isCaptured) {
                            int targetReg = bytecodeCompiler.getVariableRegister(leftVarName);

                            // Compile RHS operand ($y)
                            rightBin.right.accept(bytecodeCompiler);
                            int rhsReg = bytecodeCompiler.lastResultReg;

                            // Emit ADD_ASSIGN instead of ADD_SCALAR + ALIAS
                            bytecodeCompiler.emit(Opcodes.ADD_ASSIGN);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(rhsReg);

                            bytecodeCompiler.lastResultReg = targetReg;
                            return;
                        }
                    }
                }
            }

            // Handle ${block} = value and $$var = value (symbolic references)
            // We need to evaluate the LHS FIRST to get the variable name,
            // then evaluate the RHS, to ensure the RHS doesn't clobber the LHS registers
            if (node.left instanceof OperatorNode leftOp && leftOp.operator.equals("$")) {
                boolean strictRefsEnabled = bytecodeCompiler.isStrictRefsEnabled();

                if (leftOp.operand instanceof BlockNode) {
                    // ${block} = value — mirrors JVM EmitVariable.java case "$"
                    BlockNode block = (BlockNode) leftOp.operand;
                    block.accept(bytecodeCompiler);
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
                    node.right.accept(bytecodeCompiler);
                    int valueReg = bytecodeCompiler.lastResultReg;
                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                    bytecodeCompiler.emitReg(derefReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = valueReg;
                    return;
                } else if (leftOp.operand instanceof OperatorNode) {
                    // $$var = value — mirrors JVM EmitVariable.java case "$"
                    leftOp.operand.accept(bytecodeCompiler);
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

                    node.right.accept(bytecodeCompiler);
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
            node.right.accept(bytecodeCompiler);
            int valueReg = bytecodeCompiler.lastResultReg;

            // Assign to LHS
            if (node.left instanceof OperatorNode) {
                OperatorNode leftOp = (OperatorNode) node.left;
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
                    if (savedContext == RuntimeContextType.SCALAR) {
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

                    // Populate hash from list using setFromList
                    bytecodeCompiler.emit(Opcodes.HASH_SET_FROM_LIST);
                    bytecodeCompiler.emitReg(hashReg);
                    bytecodeCompiler.emitReg(valueReg);

                    // In scalar context, return the hash size; in list context, return the hash
                    if (savedContext == RuntimeContextType.SCALAR) {
                        // Convert hash to scalar (returns bucket info like "3/8")
                        int sizeReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.ARRAY_SIZE);
                        bytecodeCompiler.emitReg(sizeReg);
                        bytecodeCompiler.emitReg(hashReg);
                        bytecodeCompiler.lastResultReg = sizeReg;
                    } else {
                        bytecodeCompiler.lastResultReg = hashReg;
                    }
                } else if (leftOp.operator.equals("our")) {
                    // Assignment to our variable: our $x = value or our @x = value or our %x = value
                    // Compile the our declaration first (which loads the global into a register)
                    leftOp.accept(bytecodeCompiler);
                    int targetReg = bytecodeCompiler.lastResultReg;

                    // Now assign the RHS value to the target register
                    // The target register contains either a scalar, array, or hash
                    // We need to determine which and use the appropriate assignment

                    // Extract the sigil from our operand
                    if (leftOp.operand instanceof OperatorNode) {
                        OperatorNode sigilOp = (OperatorNode) leftOp.operand;
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
                    } else if (leftOp.operand instanceof ListNode) {
                        // our ($a, $b) = ... - list declaration with assignment
                        // Uses SET_FROM_LIST to match JVM backend's setFromList() semantics
                        ListNode listNode = (ListNode) leftOp.operand;

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
                        bytecodeCompiler.currentCallContext = savedContext;
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
                    leftOp.operand.accept(bytecodeCompiler);
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
                    leftOp.accept(bytecodeCompiler);
                    int globReg = bytecodeCompiler.lastResultReg;

                    bytecodeCompiler.emit(Opcodes.STORE_GLOB);
                    bytecodeCompiler.emitReg(globReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = globReg;
                } else if (leftOp.operator.equals("pos")) {
                    // pos($var) = value - lvalue assignment to regex position
                    // pos() returns a PosLvalueScalar that can be assigned to
                    node.left.accept(bytecodeCompiler);
                    int lvalueReg = bytecodeCompiler.lastResultReg;

                    // Use SET_SCALAR to assign through the lvalue
                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                    bytecodeCompiler.emitReg(lvalueReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = valueReg;
                } else if (leftOp.operator.equals("substr")) {
                    node.left.accept(bytecodeCompiler);
                    int lvalueReg = bytecodeCompiler.lastResultReg;

                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                    bytecodeCompiler.emitReg(lvalueReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = valueReg;
                } else if (leftOp.operator.equals("@") && leftOp.operand instanceof OperatorNode) {
                    // Array dereference assignment: @$r = ...
                    // The operand should be a scalar variable containing an array reference
                    OperatorNode derefOp = (OperatorNode) leftOp.operand;

                    if (derefOp.operator.equals("$")) {
                        // Compile the scalar to get the array reference
                        derefOp.accept(bytecodeCompiler);
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
                        if (savedContext == RuntimeContextType.SCALAR) {
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
            } else if (node.left instanceof BinaryOperatorNode) {
                BinaryOperatorNode leftBin = (BinaryOperatorNode) node.left;

                // Handle array slice assignment: @array[1, 3, 5] = (20, 30, 40)
                if (leftBin.operator.equals("[") && leftBin.left instanceof OperatorNode) {
                    OperatorNode arrayOp = (OperatorNode) leftBin.left;

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
                            indexNode.accept(bytecodeCompiler);
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

                        // Compile values (RHS of assignment)
                        node.right.accept(bytecodeCompiler);
                        int valuesReg = bytecodeCompiler.lastResultReg;

                        // Emit direct opcode ARRAY_SLICE_SET
                        bytecodeCompiler.emit(Opcodes.ARRAY_SLICE_SET);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emitReg(indicesReg);
                        bytecodeCompiler.emitReg(valuesReg);

                        bytecodeCompiler.lastResultReg = arrayReg;
                        bytecodeCompiler.currentCallContext = savedContext;
                        return;
                    }
                }

                // Handle single element array assignment
                // For: $array[index] = value or $matrix[3][0] = value
                if (leftBin.operator.equals("[")) {
                    int arrayReg;

                    // Check if left side is a variable or multidimensional access
                    if (leftBin.left instanceof OperatorNode) {
                        OperatorNode arrayOp = (OperatorNode) leftBin.left;

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
                        leftBin.left.accept(bytecodeCompiler);
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

                    indexNode.elements.get(0).accept(bytecodeCompiler);
                    int indexReg = bytecodeCompiler.lastResultReg;

                    // Compile RHS value
                    node.right.accept(bytecodeCompiler);
                    int assignValueReg = bytecodeCompiler.lastResultReg;

                    // Emit ARRAY_SET
                    bytecodeCompiler.emit(Opcodes.ARRAY_SET);
                    bytecodeCompiler.emitReg(arrayReg);
                    bytecodeCompiler.emitReg(indexReg);
                    bytecodeCompiler.emitReg(assignValueReg);

                    bytecodeCompiler.lastResultReg = assignValueReg;
                    bytecodeCompiler.currentCallContext = savedContext;
                    return;
                } else if (leftBin.operator.equals("{")) {
                    // Hash element/slice assignment
                    // $hash{key} = value (scalar element)
                    // @hash{keys} = values (slice)

                    // 1. Get hash variable (leftBin.left)
                    int hashReg;
                    if (leftBin.left instanceof OperatorNode) {
                        OperatorNode hashOp = (OperatorNode) leftBin.left;

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
                                hashOp.operand.accept(bytecodeCompiler);
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
                            if (!(leftBin.right instanceof HashLiteralNode)) {
                                bytecodeCompiler.throwCompilerException("Hash slice assignment requires HashLiteralNode");
                                return;
                            }
                            HashLiteralNode keysNode = (HashLiteralNode) leftBin.right;
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
                                    // Expression key
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

                            // Compile RHS values
                            node.right.accept(bytecodeCompiler);
                            int valuesReg = bytecodeCompiler.lastResultReg;

                            // Emit direct opcode HASH_SLICE_SET
                            bytecodeCompiler.emit(Opcodes.HASH_SLICE_SET);
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emitReg(keysListReg);
                            bytecodeCompiler.emitReg(valuesReg);

                            bytecodeCompiler.lastResultReg = valuesReg;
                            bytecodeCompiler.currentCallContext = savedContext;
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
                                hashOp.operand.accept(bytecodeCompiler);
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
                        leftBin.left.accept(bytecodeCompiler);
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
                    if (!(leftBin.right instanceof HashLiteralNode)) {
                        bytecodeCompiler.throwCompilerException("Hash assignment requires HashLiteralNode on right side");
                        return;
                    }
                    HashLiteralNode keyNode = (HashLiteralNode) leftBin.right;
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
                        // Expression key: $hash{$var} or $hash{func()}
                        keyElement.accept(bytecodeCompiler);
                        keyReg = bytecodeCompiler.lastResultReg;
                    }

                    // 3. Compile RHS value
                    node.right.accept(bytecodeCompiler);
                    int hashValueReg = bytecodeCompiler.lastResultReg;

                    // 4. Emit HASH_SET
                    bytecodeCompiler.emit(Opcodes.HASH_SET);
                    bytecodeCompiler.emitReg(hashReg);
                    bytecodeCompiler.emitReg(keyReg);
                    bytecodeCompiler.emitReg(hashValueReg);

                    bytecodeCompiler.lastResultReg = hashValueReg;
                    bytecodeCompiler.currentCallContext = savedContext;
                    return;
                }

                // Handle arrow dereference assignment: $ref->{key} = value or $$ref{key} = value
                // These parse as BinaryOperatorNode("->", expr, HashLiteralNode/ArrayLiteralNode)
                if (leftBin.operator.equals("->")) {
                    Node rightSide = leftBin.right;
                    if (rightSide instanceof HashLiteralNode hashKey) {
                        // $ref->{key} = value — hash element via reference
                        leftBin.left.accept(bytecodeCompiler);
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
                                keyElement.accept(bytecodeCompiler);
                                keyReg = bytecodeCompiler.lastResultReg;
                            }
                        } else {
                            bytecodeCompiler.throwCompilerException("Hash key required for arrow assignment");
                            return;
                        }

                        // Compile RHS and emit HASH_SET
                        node.right.accept(bytecodeCompiler);
                        int valReg = bytecodeCompiler.lastResultReg;
                        bytecodeCompiler.emit(Opcodes.HASH_SET);
                        bytecodeCompiler.emitReg(hashReg);
                        bytecodeCompiler.emitReg(keyReg);
                        bytecodeCompiler.emitReg(valReg);
                        bytecodeCompiler.lastResultReg = valReg;
                        bytecodeCompiler.currentCallContext = savedContext;
                        return;
                    } else if (rightSide instanceof ArrayLiteralNode arrayIdx) {
                        // $ref->[index] = value — array element via reference
                        leftBin.left.accept(bytecodeCompiler);
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
                        arrayIdx.elements.get(0).accept(bytecodeCompiler);
                        int idxReg = bytecodeCompiler.lastResultReg;

                        // Compile RHS and emit ARRAY_SET
                        node.right.accept(bytecodeCompiler);
                        int valReg = bytecodeCompiler.lastResultReg;
                        bytecodeCompiler.emit(Opcodes.ARRAY_SET);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emitReg(idxReg);
                        bytecodeCompiler.emitReg(valReg);
                        bytecodeCompiler.lastResultReg = valReg;
                        bytecodeCompiler.currentCallContext = savedContext;
                        return;
                    }
                }

                // Handle lvalue subroutine: f() = value
                // When a function is called in lvalue context, it returns a RuntimeBaseProxy
                // that wraps a mutable reference. We can assign to it using SET_SCALAR.
                if (leftBin.operator.equals("(")) {
                    // Call the function (which returns a RuntimeBaseProxy in lvalue context)
                    node.left.accept(bytecodeCompiler);
                    int lvalueReg = bytecodeCompiler.lastResultReg;

                    // Compile RHS
                    node.right.accept(bytecodeCompiler);
                    int rhsReg = bytecodeCompiler.lastResultReg;

                    // Assign to the lvalue using SET_SCALAR
                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                    bytecodeCompiler.emitReg(lvalueReg);
                    bytecodeCompiler.emitReg(rhsReg);

                    bytecodeCompiler.lastResultReg = rhsReg;
                    bytecodeCompiler.currentCallContext = savedContext;
                    return;
                }

                bytecodeCompiler.throwCompilerException("Assignment to non-identifier not yet supported: " + node.left.getClass().getSimpleName());
            } else if (node.left instanceof TernaryOperatorNode) {
                LValueVisitor.getContext(node.left);
                node.left.accept(bytecodeCompiler);
                int lvalueReg = bytecodeCompiler.lastResultReg;
                node.right.accept(bytecodeCompiler);
                int rhsReg = bytecodeCompiler.lastResultReg;
                bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                bytecodeCompiler.emitReg(lvalueReg);
                bytecodeCompiler.emitReg(rhsReg);
                bytecodeCompiler.lastResultReg = rhsReg;
                bytecodeCompiler.currentCallContext = savedContext;
            } else if (node.left instanceof ListNode) {
                // List assignment: ($a, $b) = ... or () = ...
                // In scalar context, returns the number of elements on RHS
                // In list context, returns the RHS list
                LValueVisitor.getContext(node.left);
                ListNode listNode = (ListNode) node.left;

                // RHS was already compiled at the "regular assignment" fallthrough above (valueReg).
                // Reuse it instead of compiling again.
                int rhsReg = valueReg;

                // Convert RHS to RuntimeList if needed
                int rhsListReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.SCALAR_TO_LIST);
                bytecodeCompiler.emitReg(rhsListReg);
                bytecodeCompiler.emitReg(rhsReg);

                // Resolve all LHS variables and collect their registers
                List<Integer> varRegs = new ArrayList<>();
                for (Node lhsElement : listNode.elements) {
                    if (lhsElement instanceof OperatorNode lhsOp && lhsOp.operand instanceof IdentifierNode idNode) {
                        String sigil = lhsOp.operator;
                        String varName = sigil + idNode.name;

                        if (sigil.equals("$")) {
                            if (bytecodeCompiler.hasVariable(varName)) {
                                int targetReg = bytecodeCompiler.getVariableRegister(varName);
                                if (!((bytecodeCompiler.capturedVarIndices != null && bytecodeCompiler.capturedVarIndices.containsKey(varName))
                                        || bytecodeCompiler.closureCapturedVarNames.contains(varName))) {
                                    bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                                    bytecodeCompiler.emitReg(targetReg);
                                }
                                varRegs.add(targetReg);
                            } else {
                                if (bytecodeCompiler.shouldBlockGlobalUnderStrictVars(varName)) {
                                    bytecodeCompiler.throwCompilerException("Global symbol \"" + varName + "\" requires explicit package name");
                                }
                                String normalizedName = NameNormalizer.normalizeVariableName(idNode.name, bytecodeCompiler.getCurrentPackage());
                                int nameIdx = bytecodeCompiler.addToStringPool(normalizedName);
                                int globalReg = bytecodeCompiler.allocateRegister();
                                bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                                bytecodeCompiler.emitReg(globalReg);
                                bytecodeCompiler.emit(nameIdx);
                                varRegs.add(globalReg);
                            }
                        } else if (sigil.equals("@")) {
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
                                String globalName = NameNormalizer.normalizeVariableName(idNode.name, bytecodeCompiler.getCurrentPackage());
                                int nameIdx = bytecodeCompiler.addToStringPool(globalName);
                                bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                                bytecodeCompiler.emitReg(arrayReg);
                                bytecodeCompiler.emit(nameIdx);
                            }
                            varRegs.add(arrayReg);
                        } else if (sigil.equals("%")) {
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
                                String globalName = NameNormalizer.normalizeVariableName(idNode.name, bytecodeCompiler.getCurrentPackage());
                                int nameIdx = bytecodeCompiler.addToStringPool(globalName);
                                bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_HASH);
                                bytecodeCompiler.emitReg(hashReg);
                                bytecodeCompiler.emit(nameIdx);
                            }
                            varRegs.add(hashReg);
                        }
                    }
                }

                int countReg = -1;
                if (savedContext == RuntimeContextType.SCALAR) {
                    countReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.ARRAY_SIZE);
                    bytecodeCompiler.emitReg(countReg);
                    bytecodeCompiler.emitReg(rhsListReg);
                }

                // Build LHS list and assign via SET_FROM_LIST
                if (!varRegs.isEmpty()) {
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
                }

                if (countReg >= 0) {
                    bytecodeCompiler.lastResultReg = countReg;
                } else {
                    bytecodeCompiler.lastResultReg = rhsListReg;
                }

                bytecodeCompiler.currentCallContext = savedContext;
            } else {
                bytecodeCompiler.throwCompilerException("Assignment to non-identifier not yet supported: " + node.left.getClass().getSimpleName());
            }

        } finally {
            // Always restore the calling context
            bytecodeCompiler.currentCallContext = savedContext;
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
        }
        bytecodeCompiler.throwCompilerException("$# assignment requires array variable");
        return -1;
    }
}

