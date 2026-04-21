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
            // Compile index in SCALAR context
            bc.compileNode(indexNode.elements.get(0), -1, RuntimeContextType.SCALAR);
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
        if (hashAccess.left instanceof OperatorNode leftOp && leftOp.operator.equals("%")) {
            visitDeleteHashKVSlice(bc, node, hashAccess, leftOp);
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
                // Compile key in SCALAR context
                bc.compileNode(keyElement, -1, RuntimeContextType.SCALAR);
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

    private static void visitDeleteHashKVSlice(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode hashAccess, OperatorNode leftOp) {
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
            bc.throwCompilerException("Hash kv-slice delete requires identifier");
            return;
        }
        if (!(hashAccess.right instanceof HashLiteralNode keysNode)) {
            bc.throwCompilerException("Hash kv-slice delete requires HashLiteralNode");
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
                // Compile key in SCALAR context
                bc.compileNode(keyElement, -1, RuntimeContextType.SCALAR);
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
        bc.emit(Opcodes.HASH_KV_SLICE_DELETE);
        bc.emitReg(rd);
        bc.emitReg(hashReg);
        bc.emitReg(keysListReg);
        bc.lastResultReg = rd;
    }

    private static void visitDeleteArray(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode arrayAccess) {
        if (arrayAccess.left instanceof OperatorNode leftOp && leftOp.operator.equals("@")) {
            visitDeleteArraySlice(bc, node, arrayAccess, leftOp);
            return;
        }
        if (arrayAccess.left instanceof OperatorNode leftOp && leftOp.operator.equals("%")) {
            visitDeleteArrayKVSlice(bc, node, arrayAccess, leftOp);
            return;
        }
        // Perl allows chains like $f->[W][0] where the arrow is elided between
        // consecutive subscripts. At the parser level that yields an outer "["
        // whose left is itself a "->" or another "[" (or any scalar expression
        // producing an array reference). Treat this as a postfix deref: compile
        // the left as a scalar, deref to an array, then index.
        boolean leftIsArrayRefExpr =
                arrayAccess.left instanceof BinaryOperatorNode binLeft
                        && (binLeft.operator.equals("->") || binLeft.operator.equals("[")
                                || binLeft.operator.equals("{"));
        if (leftIsArrayRefExpr) {
            bc.compileNode(arrayAccess.left, -1, RuntimeContextType.SCALAR);
            int refReg = bc.lastResultReg;
            int arrayReg = derefArray(bc, refReg, node.getIndex());
            int indexReg = compileArrayIndex(bc, arrayAccess);
            int rd = bc.allocateOutputRegister();
            bc.emit(Opcodes.ARRAY_DELETE);
            bc.emitReg(rd);
            bc.emitReg(arrayReg);
            bc.emitReg(indexReg);
            bc.lastResultReg = rd;
            return;
        }
        int arrayReg = compileArrayForExistsDelete(bc, arrayAccess, node.getIndex());
        int indexReg = compileArrayIndex(bc, arrayAccess);
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.ARRAY_DELETE);
        bc.emitReg(rd);
        bc.emitReg(arrayReg);
        bc.emitReg(indexReg);
        bc.lastResultReg = rd;
    }

    private static void visitDeleteArraySlice(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode arrayAccess, OperatorNode leftOp) {
        int arrayReg;
        if (leftOp.operand instanceof IdentifierNode id) {
            String arrayVarName = "@" + id.name;
            if (bc.hasVariable(arrayVarName)) {
                arrayReg = bc.getVariableRegister(arrayVarName);
            } else {
                arrayReg = bc.allocateRegister();
                String globalArrayName = NameNormalizer.normalizeVariableName(id.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalArrayName);
                bc.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                bc.emitReg(arrayReg);
                bc.emit(nameIdx);
            }
        } else {
            bc.throwCompilerException("Array slice delete requires identifier");
            return;
        }
        if (!(arrayAccess.right instanceof ArrayLiteralNode indicesNode)) {
            bc.throwCompilerException("Array slice delete requires ArrayLiteralNode");
            return;
        }
        List<Integer> indexRegs = new ArrayList<>();
        for (Node indexElement : indicesNode.elements) {
            // Compile index in SCALAR context
            bc.compileNode(indexElement, -1, RuntimeContextType.SCALAR);
            indexRegs.add(bc.lastResultReg);
        }
        int indicesListReg = bc.allocateRegister();
        bc.emit(Opcodes.CREATE_LIST);
        bc.emitReg(indicesListReg);
        bc.emit(indexRegs.size());
        for (int indexReg : indexRegs) {
            bc.emitReg(indexReg);
        }
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.ARRAY_SLICE_DELETE);
        bc.emitReg(rd);
        bc.emitReg(arrayReg);
        bc.emitReg(indicesListReg);
        bc.lastResultReg = rd;
    }

    private static void visitDeleteArrayKVSlice(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode arrayAccess, OperatorNode leftOp) {
        int arrayReg;
        if (leftOp.operand instanceof IdentifierNode id) {
            String arrayVarName = "@" + id.name;
            if (bc.hasVariable(arrayVarName)) {
                arrayReg = bc.getVariableRegister(arrayVarName);
            } else {
                arrayReg = bc.allocateRegister();
                String globalArrayName = NameNormalizer.normalizeVariableName(id.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalArrayName);
                bc.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                bc.emitReg(arrayReg);
                bc.emit(nameIdx);
            }
        } else {
            bc.throwCompilerException("Array kv-slice delete requires identifier");
            return;
        }
        if (!(arrayAccess.right instanceof ArrayLiteralNode indicesNode)) {
            bc.throwCompilerException("Array kv-slice delete requires ArrayLiteralNode");
            return;
        }
        List<Integer> indexRegs = new ArrayList<>();
        for (Node indexElement : indicesNode.elements) {
            // Compile index in SCALAR context
            bc.compileNode(indexElement, -1, RuntimeContextType.SCALAR);
            indexRegs.add(bc.lastResultReg);
        }
        int indicesListReg = bc.allocateRegister();
        bc.emit(Opcodes.CREATE_LIST);
        bc.emitReg(indicesListReg);
        bc.emit(indexRegs.size());
        for (int indexReg : indexRegs) {
            bc.emitReg(indexReg);
        }
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.ARRAY_KV_SLICE_DELETE);
        bc.emitReg(rd);
        bc.emitReg(arrayReg);
        bc.emitReg(indicesListReg);
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
            // Compile index in SCALAR context
            bc.compileNode(indexNode.elements.get(0), -1, RuntimeContextType.SCALAR);
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
                // Compile operand in SCALAR context to ensure we get a result register
                bc.compileNode(leftOp.operand, -1, RuntimeContextType.SCALAR);
                int scalarReg = bc.lastResultReg;
                return derefHash(bc, scalarReg, tokenIndex);
            }
        } else if (hashAccess.left instanceof BinaryOperatorNode) {
            // Compile in SCALAR context to ensure we get a result register
            bc.compileNode(hashAccess.left, -1, RuntimeContextType.SCALAR);
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
                // Compile in scalar context to ensure we get a RuntimeScalar
                bc.compileNode(keyElement, -1, RuntimeContextType.SCALAR);
                return bc.lastResultReg;
            }
        } else {
            // Compile in scalar context to ensure we get a RuntimeScalar
            bc.compileNode(keySpec, -1, RuntimeContextType.SCALAR);
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
        // Compile in scalar context to ensure we get a RuntimeScalar
        bc.compileNode(indexNode.elements.get(0), -1, RuntimeContextType.SCALAR);
        return bc.lastResultReg;
    }

    /**
     * Handles `delete local` in the bytecode interpreter.
     * Mirrors visitDelete but uses HASH_DELETE_LOCAL / ARRAY_DELETE_LOCAL opcodes.
     */
    public static void visitDeleteLocal(BytecodeCompiler bc, OperatorNode node) {
        if (node.operand == null || !(node.operand instanceof ListNode list) || list.elements.isEmpty()) {
            bc.throwCompilerException("delete local requires an argument");
            return;
        }
        Node arg = list.elements.get(0);
        if (arg instanceof BinaryOperatorNode binOp) {
            switch (binOp.operator) {
                case "{" -> visitDeleteLocalHash(bc, node, binOp);
                case "[" -> visitDeleteLocalArray(bc, node, binOp);
                case "->" -> visitDeleteLocalArrow(bc, node, binOp);
                default -> bc.throwCompilerException("delete local requires hash or array element");
            }
        } else {
            bc.throwCompilerException("delete local requires hash or array element");
        }
    }

    private static void visitDeleteLocalHash(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode hashAccess) {
        if (hashAccess.left instanceof OperatorNode leftOp && leftOp.operator.equals("@")) {
            visitDeleteLocalHashSlice(bc, node, hashAccess, leftOp);
            return;
        }
        int hashReg = resolveHashFromBinaryOp(bc, hashAccess, node.getIndex());
        int keyReg = compileHashKey(bc, hashAccess.right);
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.HASH_DELETE_LOCAL);
        bc.emitReg(rd);
        bc.emitReg(hashReg);
        bc.emitReg(keyReg);
        bc.lastResultReg = rd;
    }

    private static void visitDeleteLocalHashSlice(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode hashAccess, OperatorNode leftOp) {
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
            bc.throwCompilerException("Hash slice delete local requires identifier");
            return;
        }
        if (!(hashAccess.right instanceof HashLiteralNode keysNode)) {
            bc.throwCompilerException("Hash slice delete local requires HashLiteralNode");
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
                bc.compileNode(keyElement, -1, RuntimeContextType.SCALAR);
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
        bc.emit(Opcodes.HASH_SLICE_DELETE_LOCAL);
        bc.emitReg(rd);
        bc.emitReg(hashReg);
        bc.emitReg(keysListReg);
        bc.lastResultReg = rd;
    }

    private static void visitDeleteLocalArray(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode arrayAccess) {
        if (arrayAccess.left instanceof OperatorNode leftOp && leftOp.operator.equals("@")) {
            visitDeleteLocalArraySlice(bc, node, arrayAccess, leftOp);
            return;
        }
        int arrayReg = compileArrayForExistsDelete(bc, arrayAccess, node.getIndex());
        int indexReg = compileArrayIndex(bc, arrayAccess);
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.ARRAY_DELETE_LOCAL);
        bc.emitReg(rd);
        bc.emitReg(arrayReg);
        bc.emitReg(indexReg);
        bc.lastResultReg = rd;
    }

    private static void visitDeleteLocalArraySlice(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode arrayAccess, OperatorNode leftOp) {
        int arrayReg;
        if (leftOp.operand instanceof IdentifierNode id) {
            String arrayVarName = "@" + id.name;
            if (bc.hasVariable(arrayVarName)) {
                arrayReg = bc.getVariableRegister(arrayVarName);
            } else {
                arrayReg = bc.allocateRegister();
                String globalArrayName = NameNormalizer.normalizeVariableName(id.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalArrayName);
                bc.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                bc.emitReg(arrayReg);
                bc.emit(nameIdx);
            }
        } else {
            bc.throwCompilerException("Array slice delete local requires identifier");
            return;
        }
        if (!(arrayAccess.right instanceof ArrayLiteralNode indicesNode)) {
            bc.throwCompilerException("Array slice delete local requires ArrayLiteralNode");
            return;
        }
        List<Integer> indexRegs = new ArrayList<>();
        for (Node indexElement : indicesNode.elements) {
            bc.compileNode(indexElement, -1, RuntimeContextType.SCALAR);
            indexRegs.add(bc.lastResultReg);
        }
        int indicesListReg = bc.allocateRegister();
        bc.emit(Opcodes.CREATE_LIST);
        bc.emitReg(indicesListReg);
        bc.emit(indexRegs.size());
        for (int indexReg : indexRegs) {
            bc.emitReg(indexReg);
        }
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.ARRAY_SLICE_DELETE_LOCAL);
        bc.emitReg(rd);
        bc.emitReg(arrayReg);
        bc.emitReg(indicesListReg);
        bc.lastResultReg = rd;
    }

    private static void visitDeleteLocalArrow(BytecodeCompiler bc, OperatorNode node, BinaryOperatorNode arrowAccess) {
        if (arrowAccess.right instanceof HashLiteralNode) {
            bc.compileNode(arrowAccess.left, -1, RuntimeContextType.SCALAR);
            int refReg = bc.lastResultReg;
            int hashReg = derefHash(bc, refReg, node.getIndex());
            int keyReg = compileHashKey(bc, arrowAccess.right);
            int rd = bc.allocateOutputRegister();
            bc.emit(Opcodes.HASH_DELETE_LOCAL);
            bc.emitReg(rd);
            bc.emitReg(hashReg);
            bc.emitReg(keyReg);
            bc.lastResultReg = rd;
        } else if (arrowAccess.right instanceof ArrayLiteralNode indexNode) {
            bc.compileNode(arrowAccess.left, -1, RuntimeContextType.SCALAR);
            int refReg = bc.lastResultReg;
            int arrayReg = derefArray(bc, refReg, node.getIndex());
            if (indexNode.elements.isEmpty()) {
                bc.throwCompilerException("Array index required for delete local");
                return;
            }
            bc.compileNode(indexNode.elements.get(0), -1, RuntimeContextType.SCALAR);
            int indexReg = bc.lastResultReg;
            int rd = bc.allocateOutputRegister();
            bc.emit(Opcodes.ARRAY_DELETE_LOCAL);
            bc.emitReg(rd);
            bc.emitReg(arrayReg);
            bc.emitReg(indexReg);
            bc.lastResultReg = rd;
        } else {
            bc.throwCompilerException("delete local requires hash or array element");
        }
    }
}
