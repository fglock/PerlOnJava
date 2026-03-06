package org.perlonjava.backend.bytecode;

import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.util.ArrayList;
import java.util.List;

public class CompileExistsDelete {

    public static void visitExists(BytecodeCompiler bc, OperatorNode node) {
        if (node.operand == null || !(node.operand instanceof ListNode list) || list.elements.isEmpty()) {
            bc.throwCompilerException("exists requires an argument");
            return;
        }
        Node arg = list.elements.get(0);
        if (arg instanceof BinaryOperatorNode binOp) {
            switch (binOp.operator) {
                case "{" -> visitExistsHash(bc, node, binOp);
                case "[" -> visitExistsArray(bc, node, binOp);
                case "->" -> visitExistsArrow(bc, node, binOp);
                default -> visitExistsGeneric(bc, arg);
            }
        } else {
            visitExistsGeneric(bc, arg);
        }
    }

    private static void visitExistsHash(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode hashAccess) {
        int hashReg = resolveHashFromBinaryOp(bc, hashAccess, node.getIndex());
        int keyReg = compileHashKey(bc, hashAccess.right);
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.HASH_EXISTS);
        bc.emitReg(rd);
        bc.emitReg(hashReg);
        bc.emitReg(keyReg);
        bc.lastResultReg = rd;
    }

    private static void visitExistsArray(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode arrayAccess) {
        int arrayReg = compileArrayForExistsDelete(bc, arrayAccess, node.getIndex());
        int indexReg = compileArrayIndex(bc, arrayAccess);
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.ARRAY_EXISTS);
        bc.emitReg(rd);
        bc.emitReg(arrayReg);
        bc.emitReg(indexReg);
        bc.lastResultReg = rd;
    }

    private static void visitExistsArrow(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode arrowAccess) {
        if (arrowAccess.right instanceof HashLiteralNode) {
            bc.compileNode(arrowAccess.left, -1, RuntimeContextType.SCALAR);
            int refReg = bc.lastResultReg;
            int hashReg = derefHash(bc, refReg, node.getIndex());
            int keyReg = compileHashKey(bc, arrowAccess.right);
            int rd = bc.allocateOutputRegister();
            bc.emit(Opcodes.HASH_EXISTS);
            bc.emitReg(rd);
            bc.emitReg(hashReg);
            bc.emitReg(keyReg);
            bc.lastResultReg = rd;
        } else if (arrowAccess.right instanceof ArrayLiteralNode indexNode) {
            bc.compileNode(arrowAccess.left, -1, RuntimeContextType.SCALAR);
            int refReg = bc.lastResultReg;
            int arrayReg = derefArray(bc, refReg, node.getIndex());
            if (indexNode.elements.isEmpty()) {
                bc.throwCompilerException("Array index required for exists");
                return;
            }
            indexNode.elements.get(0).accept(bc);
            int indexReg = bc.lastResultReg;
            int rd = bc.allocateOutputRegister();
            bc.emit(Opcodes.ARRAY_EXISTS);
            bc.emitReg(rd);
            bc.emitReg(arrayReg);
            bc.emitReg(indexReg);
            bc.lastResultReg = rd;
        } else {
            visitExistsGeneric(bc, arrowAccess);
        }
    }

    private static void visitExistsGeneric(BytecodeCompiler bc, Node arg) {
        arg.accept(bc);
        int argReg = bc.lastResultReg;
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.EXISTS);
        bc.emitReg(rd);
        bc.emitReg(argReg);
        bc.lastResultReg = rd;
    }

    public static void visitDelete(BytecodeCompiler bc, OperatorNode node) {
        if (node.operand == null || !(node.operand instanceof ListNode list) || list.elements.isEmpty()) {
            bc.throwCompilerException("delete requires an argument");
            return;
        }
        Node arg = list.elements.get(0);
        if (arg instanceof BinaryOperatorNode binOp) {
            switch (binOp.operator) {
                case "{" -> visitDeleteHash(bc, node, binOp);
                case "[" -> visitDeleteArray(bc, node, binOp);
                case "->" -> visitDeleteArrow(bc, node, binOp);
                default -> visitDeleteGeneric(bc, arg);
            }
        } else {
            visitDeleteGeneric(bc, arg);
        }
    }

    private static void visitDeleteHash(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode hashAccess) {
        if (hashAccess.left instanceof OperatorNode leftOp && leftOp.operator.equals("@")) {
            visitDeleteHashSlice(bc, node, hashAccess, leftOp);
            return;
        }
        int hashReg = resolveHashFromBinaryOp(bc, hashAccess, node.getIndex());
        int keyReg = compileHashKey(bc, hashAccess.right);
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.HASH_DELETE);
        bc.emitReg(rd);
        bc.emitReg(hashReg);
        bc.emitReg(keyReg);
        bc.lastResultReg = rd;
    }

    private static void visitDeleteHashSlice(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode hashAccess, OperatorNode leftOp) {
        int hashReg;
        if (leftOp.operand instanceof IdentifierNode id) {
            String hashVarName = "%" + id.name;
            if (bc.hasVariable(hashVarName)) {
                hashReg = bc.getVariableRegister(hashVarName);
            } else {
                hashReg = bc.allocateRegister();
                String globalHashName = NameNormalizer.normalizeVariableName(id.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalHashName);
                bc.emit(Opcodes.LOAD_GLOBAL_HASH);
                bc.emitReg(hashReg);
                bc.emit(nameIdx);
            }
        } else {
            bc.throwCompilerException("Hash slice delete requires identifier");
            return;
        }
        if (!(hashAccess.right instanceof HashLiteralNode keysNode)) {
            bc.throwCompilerException("Hash slice delete requires HashLiteralNode");
            return;
        }
        List<Integer> keyRegs = new ArrayList<>();
        for (Node keyElement : keysNode.elements) {
            if (keyElement instanceof IdentifierNode keyId) {
                int keyReg = bc.allocateRegister();
                int keyIdx = bc.addToStringPool(keyId.name);
                bc.emit(Opcodes.LOAD_STRING);
                bc.emitReg(keyReg);
                bc.emit(keyIdx);
                keyRegs.add(keyReg);
            } else {
                keyElement.accept(bc);
                keyRegs.add(bc.lastResultReg);
            }
        }
        int keysListReg = bc.allocateRegister();
        bc.emit(Opcodes.CREATE_LIST);
        bc.emitReg(keysListReg);
        bc.emit(keyRegs.size());
        for (int keyReg : keyRegs) {
            bc.emitReg(keyReg);
        }
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.HASH_SLICE_DELETE);
        bc.emitReg(rd);
        bc.emitReg(hashReg);
        bc.emitReg(keysListReg);
        bc.lastResultReg = rd;
    }

    private static void visitDeleteArray(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode arrayAccess) {
        int arrayReg = compileArrayForExistsDelete(bc, arrayAccess, node.getIndex());
        int indexReg = compileArrayIndex(bc, arrayAccess);
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.ARRAY_DELETE);
        bc.emitReg(rd);
        bc.emitReg(arrayReg);
        bc.emitReg(indexReg);
        bc.lastResultReg = rd;
    }

    private static void visitDeleteArrow(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode arrowAccess) {
        if (arrowAccess.right instanceof HashLiteralNode) {
            bc.compileNode(arrowAccess.left, -1, RuntimeContextType.SCALAR);
            int refReg = bc.lastResultReg;
            int hashReg = derefHash(bc, refReg, node.getIndex());
            int keyReg = compileHashKey(bc, arrowAccess.right);
            int rd = bc.allocateOutputRegister();
            bc.emit(Opcodes.HASH_DELETE);
            bc.emitReg(rd);
            bc.emitReg(hashReg);
            bc.emitReg(keyReg);
            bc.lastResultReg = rd;
        } else if (arrowAccess.right instanceof ArrayLiteralNode indexNode) {
            bc.compileNode(arrowAccess.left, -1, RuntimeContextType.SCALAR);
            int refReg = bc.lastResultReg;
            int arrayReg = derefArray(bc, refReg, node.getIndex());
            if (indexNode.elements.isEmpty()) {
                bc.throwCompilerException("Array index required for delete");
                return;
            }
            indexNode.elements.get(0).accept(bc);
            int indexReg = bc.lastResultReg;
            int rd = bc.allocateOutputRegister();
            bc.emit(Opcodes.ARRAY_DELETE);
            bc.emitReg(rd);
            bc.emitReg(arrayReg);
            bc.emitReg(indexReg);
            bc.lastResultReg = rd;
        } else {
            visitDeleteGeneric(bc, arrowAccess);
        }
    }

    private static void visitDeleteGeneric(BytecodeCompiler bc, Node arg) {
        arg.accept(bc);
        int argReg = bc.lastResultReg;
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.DELETE);
        bc.emitReg(rd);
        bc.emitReg(argReg);
        bc.lastResultReg = rd;
    }

    private static int resolveHashFromBinaryOp(BytecodeCompiler bc, BinaryOperatorNode hashAccess, int tokenIndex) {
        if (hashAccess.left instanceof OperatorNode leftOp) {
            if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode id) {
                String hashVarName = "%" + id.name;
                if (bc.hasVariable(hashVarName)) {
                    return bc.getVariableRegister(hashVarName);
                }
                int hashReg = bc.allocateRegister();
                String globalHashName = NameNormalizer.normalizeVariableName(id.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalHashName);
                bc.emit(Opcodes.LOAD_GLOBAL_HASH);
                bc.emitReg(hashReg);
                bc.emit(nameIdx);
                return hashReg;
            } else {
                leftOp.operand.accept(bc);
                int scalarReg = bc.lastResultReg;
                return derefHash(bc, scalarReg, tokenIndex);
            }
        } else if (hashAccess.left instanceof BinaryOperatorNode) {
            hashAccess.left.accept(bc);
            int scalarReg = bc.lastResultReg;
            return derefHash(bc, scalarReg, tokenIndex);
        }
        bc.throwCompilerException("Hash access requires variable or expression on left side");
        return -1;
    }

    private static int derefHash(BytecodeCompiler bc, int scalarReg, int tokenIndex) {
        int hashReg = bc.allocateRegister();
        if (bc.isStrictRefsEnabled()) {
            bc.emitWithToken(Opcodes.DEREF_HASH, tokenIndex);
            bc.emitReg(hashReg);
            bc.emitReg(scalarReg);
        } else {
            int pkgIdx = bc.addToStringPool(bc.getCurrentPackage());
            bc.emitWithToken(Opcodes.DEREF_HASH_NONSTRICT, tokenIndex);
            bc.emitReg(hashReg);
            bc.emitReg(scalarReg);
            bc.emit(pkgIdx);
        }
        return hashReg;
    }

    private static int derefArray(BytecodeCompiler bc, int scalarReg, int tokenIndex) {
        int arrayReg = bc.allocateRegister();
        if (bc.isStrictRefsEnabled()) {
            bc.emitWithToken(Opcodes.DEREF_ARRAY, tokenIndex);
            bc.emitReg(arrayReg);
            bc.emitReg(scalarReg);
        } else {
            int pkgIdx = bc.addToStringPool(bc.getCurrentPackage());
            bc.emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, tokenIndex);
            bc.emitReg(arrayReg);
            bc.emitReg(scalarReg);
            bc.emit(pkgIdx);
        }
        return arrayReg;
    }

    private static int compileHashKey(BytecodeCompiler bc, Node keySpec) {
        if (keySpec instanceof HashLiteralNode keyNode && !keyNode.elements.isEmpty()) {
            Node keyElement = keyNode.elements.get(0);
            if (keyElement instanceof IdentifierNode id) {
                int keyReg = bc.allocateRegister();
                int keyIdx = bc.addToStringPool(id.name);
                bc.emit(Opcodes.LOAD_STRING);
                bc.emitReg(keyReg);
                bc.emit(keyIdx);
                return keyReg;
            } else {
                keyElement.accept(bc);
                return bc.lastResultReg;
            }
        } else {
            keySpec.accept(bc);
            return bc.lastResultReg;
        }
    }

    private static int compileArrayForExistsDelete(BytecodeCompiler bc, BinaryOperatorNode arrayAccess, int tokenIndex) {
        if (!(arrayAccess.left instanceof OperatorNode leftOp) || !leftOp.operator.equals("$")
                || !(leftOp.operand instanceof IdentifierNode)) {
            bc.throwCompilerException("Array exists/delete requires simple array variable");
            return -1;
        }
        String varName = ((IdentifierNode) leftOp.operand).name;
        String arrayVarName = "@" + varName;
        if (bc.currentSubroutineBeginId != 0 && bc.currentSubroutineClosureVars != null
                && bc.currentSubroutineClosureVars.contains(arrayVarName)) {
            int arrayReg = bc.allocateRegister();
            int nameIdx = bc.addToStringPool(arrayVarName);
            bc.emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, tokenIndex);
            bc.emitReg(arrayReg);
            bc.emit(nameIdx);
            bc.emit(bc.currentSubroutineBeginId);
            return arrayReg;
        } else if (bc.hasVariable(arrayVarName)) {
            return bc.getVariableRegister(arrayVarName);
        } else {
            int arrayReg = bc.allocateRegister();
            String globalArrayName = NameNormalizer.normalizeVariableName(varName, bc.getCurrentPackage());
            int nameIdx = bc.addToStringPool(globalArrayName);
            bc.emit(Opcodes.LOAD_GLOBAL_ARRAY);
            bc.emitReg(arrayReg);
            bc.emit(nameIdx);
            return arrayReg;
        }
    }

    private static int compileArrayIndex(BytecodeCompiler bc, BinaryOperatorNode arrayAccess) {
        if (!(arrayAccess.right instanceof ArrayLiteralNode indexNode) || indexNode.elements.isEmpty()) {
            bc.throwCompilerException("Array exists/delete requires index");
            return -1;
        }
        indexNode.elements.get(0).accept(bc);
        return bc.lastResultReg;
    }
}
