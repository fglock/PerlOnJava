package org.perlonjava.backend.bytecode;

import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.operators.ScalarGlobOperator;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.ArrayList;
import java.util.List;

public class CompileOperator {
    private static void compileScalarOperand(BytecodeCompiler bc, OperatorNode node, String opName) {
        if (node.operand instanceof ListNode list) {
            if (!list.elements.isEmpty()) {
                bc.compileNode(list.elements.get(0), -1, RuntimeContextType.SCALAR);
            } else {
                bc.throwCompilerException(opName + " requires an argument");
            }
        } else {
            bc.compileNode(node.operand, -1, RuntimeContextType.SCALAR);
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
        // Compile index in SCALAR context
        bc.compileNode(indexNode.elements.get(0), -1, RuntimeContextType.SCALAR);
        return bc.lastResultReg;
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

    private static int resolveArrayOperand(BytecodeCompiler bc, OperatorNode node, String opName) {
        if (node.operand == null) {
            bc.throwCompilerException(opName + " requires array argument");
            return -1;
        }
        OperatorNode arrayOp;
        if (node.operand instanceof OperatorNode directOp && directOp.operator.equals("@")) {
            arrayOp = directOp;
        } else if (node.operand instanceof ListNode list && !list.elements.isEmpty()
                && list.elements.get(0) instanceof OperatorNode listOp
                && listOp.operator.equals("@")) {
            arrayOp = listOp;
        } else {
            bc.throwCompilerException(opName + " requires array variable: " + opName + " @array");
            return -1;
        }
        if (arrayOp.operand instanceof IdentifierNode id) {
            String varName = "@" + id.name;
            if (bc.hasVariable(varName)) {
                return bc.getVariableRegister(varName);
            } else {
                int arrayReg = bc.allocateRegister();
                String globalArrayName = NameNormalizer.normalizeVariableName(id.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalArrayName);
                bc.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                bc.emitReg(arrayReg);
                bc.emit(nameIdx);
                return arrayReg;
            }
        } else if (arrayOp.operand instanceof OperatorNode || arrayOp.operand instanceof BlockNode) {
            bc.compileNode(arrayOp.operand, -1, RuntimeContextType.SCALAR);
            int refReg = bc.lastResultReg;
            int arrayReg = bc.allocateRegister();
            if (bc.isStrictRefsEnabled()) {
                bc.emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                bc.emitReg(arrayReg);
                bc.emitReg(refReg);
            } else {
                int pkgIdx = bc.addToStringPool(bc.getCurrentPackage());
                bc.emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, node.getIndex());
                bc.emitReg(arrayReg);
                bc.emitReg(refReg);
                bc.emit(pkgIdx);
            }
            return arrayReg;
        } else {
            bc.throwCompilerException(opName + " requires array variable or dereferenced array");
            return -1;
        }
    }

    private static int emitLocationString(BytecodeCompiler bc, OperatorNode node) {
        String locationMsg;
        Object lineObj = node.getAnnotation("line");
        Object fileObj = node.getAnnotation("file");
        if (lineObj != null && fileObj != null) {
            locationMsg = " at " + fileObj + " line " + lineObj;
        } else if (bc.errorUtil != null) {
            // Use getSourceLocationAccurate to honor #line directives
            var loc = bc.errorUtil.getSourceLocationAccurate(node.getIndex());
            locationMsg = " at " + loc.fileName() + " line " + loc.lineNumber();
        } else {
            locationMsg = " at " + bc.sourceName + " line " + bc.sourceLine;
        }
        int locationReg = bc.allocateRegister();
        bc.emit(Opcodes.LOAD_STRING);
        bc.emitReg(locationReg);
        bc.emit(bc.addToStringPool(locationMsg));
        return locationReg;
    }

    private static int loadDefaultUnderscore(BytecodeCompiler bc) {
        if (bc.hasVariable("$_")) {
            return bc.getVariableRegister("$_");
        }
        int reg = bc.allocateRegister();
        int nameIdx = bc.addToStringPool("main::_");
        bc.emit(Opcodes.LOAD_GLOBAL_SCALAR);
        bc.emitReg(reg);
        bc.emit(nameIdx);
        return reg;
    }

    private static void visitMatchRegex(BytecodeCompiler bc, OperatorNode node) {
        if (node.operand == null || !(node.operand instanceof ListNode args) || args.elements.size() < 2) {
            bc.throwCompilerException("matchRegex requires pattern and flags");
            return;
        }
        boolean hasOModifier = false;
        Node flagsNode = args.elements.get(1);
        if (flagsNode instanceof StringNode) {
            hasOModifier = ((StringNode) flagsNode).value.contains("o");
        }
        args.elements.get(0).accept(bc);
        int patternReg = bc.lastResultReg;
        flagsNode.accept(bc);
        int flagsReg = bc.lastResultReg;
        int regexReg = bc.allocateRegister();
        if (hasOModifier) {
            int callsiteId = bc.allocateCallsiteId();
            bc.emit(Opcodes.QUOTE_REGEX_O);
            bc.emitReg(regexReg);
            bc.emitReg(patternReg);
            bc.emitReg(flagsReg);
            bc.emitReg(callsiteId);
        } else {
            bc.emit(Opcodes.QUOTE_REGEX);
            bc.emitReg(regexReg);
            bc.emitReg(patternReg);
            bc.emitReg(flagsReg);
        }
        int stringReg;
        if (args.elements.size() > 2) {
            bc.compileNode(args.elements.get(2), -1, RuntimeContextType.SCALAR);
            stringReg = bc.lastResultReg;
        } else {
            stringReg = loadDefaultUnderscore(bc);
        }
        // When 'use bytes' is in effect, convert string to UTF-8 byte representation
        if (bc.isBytesEnabled()) {
            int bytesReg = bc.allocateRegister();
            bc.emit(Opcodes.TO_BYTES_STRING);
            bc.emitReg(bytesReg);
            bc.emitReg(stringReg);
            stringReg = bytesReg;
        }
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.MATCH_REGEX);
        bc.emitReg(rd);
        bc.emitReg(stringReg);
        bc.emitReg(regexReg);
        bc.emit(bc.currentCallContext);
        bc.lastResultReg = rd;
    }

    private static void visitReplaceRegex(BytecodeCompiler bc, OperatorNode node) {
        if (node.operand == null || !(node.operand instanceof ListNode args) || args.elements.size() < 3) {
            bc.throwCompilerException("replaceRegex requires pattern, replacement, and flags");
            return;
        }
        args.elements.get(0).accept(bc);
        int patternReg = bc.lastResultReg;
        args.elements.get(1).accept(bc);
        int replacementReg = bc.lastResultReg;
        args.elements.get(2).accept(bc);
        int flagsReg = bc.lastResultReg;
        int regexReg = bc.allocateRegister();
        bc.emit(Opcodes.GET_REPLACEMENT_REGEX);
        bc.emitReg(regexReg);
        bc.emitReg(patternReg);
        bc.emitReg(replacementReg);
        bc.emitReg(flagsReg);
        bc.emitReg(1);  // @_ register - pass caller's args for replacement code
        int stringReg;
        if (args.elements.size() > 3) {
            bc.compileNode(args.elements.get(3), -1, RuntimeContextType.SCALAR);
            stringReg = bc.lastResultReg;
        } else {
            stringReg = loadDefaultUnderscore(bc);
        }
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.MATCH_REGEX);
        bc.emitReg(rd);
        bc.emitReg(stringReg);
        bc.emitReg(regexReg);
        bc.emit(bc.currentCallContext);
        bc.lastResultReg = rd;
    }

    private static void visitOpen(BytecodeCompiler bc, OperatorNode node) {
        if (node.operand == null || !(node.operand instanceof ListNode argsList) || argsList.elements.isEmpty()) {
            bc.throwCompilerException("open requires arguments");
            return;
        }
        int argsReg = bc.allocateRegister();
        bc.emit(Opcodes.NEW_ARRAY);
        bc.emitReg(argsReg);
        int fhReg = -1;
        boolean first = true;
        for (Node arg : argsList.elements) {
            arg.accept(bc);
            int elemReg = bc.lastResultReg;
            if (first) { fhReg = elemReg; first = false; }
            bc.emit(Opcodes.ARRAY_PUSH);
            bc.emitReg(argsReg);
            bc.emitReg(elemReg);
        }
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.OPEN);
        bc.emitReg(rd);
        bc.emit(bc.currentCallContext);
        bc.emitReg(argsReg);
        if (fhReg >= 0) {
            int idx0Reg = bc.allocateRegister();
            bc.emit(Opcodes.LOAD_INT);
            bc.emitReg(idx0Reg);
            bc.emit(0);
            int gotReg = bc.allocateRegister();
            bc.emit(Opcodes.ARRAY_GET);
            bc.emitReg(gotReg);
            bc.emitReg(argsReg);
            bc.emitReg(idx0Reg);
            bc.emit(Opcodes.SET_SCALAR);
            bc.emitReg(fhReg);
            bc.emitReg(gotReg);
        }
        bc.lastResultReg = rd;
    }

    private static void visitSubstr(BytecodeCompiler bc, OperatorNode node) {
        if (node.operand == null || !(node.operand instanceof ListNode args) || args.elements.size() < 2) {
            bc.throwCompilerException("substr requires at least 2 arguments");
            return;
        }
        java.util.List<Integer> argRegs = new java.util.ArrayList<>();
        for (Node arg : args.elements) {
            arg.accept(bc);
            argRegs.add(bc.lastResultReg);
        }
        int argsListReg = bc.allocateRegister();
        bc.emit(Opcodes.CREATE_LIST);
        bc.emitReg(argsListReg);
        bc.emit(argRegs.size());
        for (int argReg : argRegs) bc.emitReg(argReg);
        int rd = bc.allocateOutputRegister();
        
        // Check if substr warnings are enabled at compile time
        boolean warnSubstr = bc.symbolTable != null && bc.symbolTable.isWarningCategoryEnabled("substr");
        bc.emit(warnSubstr ? Opcodes.SUBSTR_VAR : Opcodes.SUBSTR_VAR_NO_WARN);
        
        bc.emitReg(rd);
        bc.emitReg(argsListReg);
        bc.emit(bc.currentCallContext);
        bc.lastResultReg = rd;
    }

    private static void visitChomp(BytecodeCompiler bc, OperatorNode node) {
        boolean noArgs = node.operand == null ||
                (node.operand instanceof ListNode l && l.elements.isEmpty());
        int targetReg;
        if (noArgs) {
            targetReg = loadDefaultUnderscore(bc);
        } else {
            // Compile operand in LIST context so RuntimeList.chomp() handles all elements
            bc.compileNode(node.operand, -1, RuntimeContextType.LIST);
            targetReg = bc.lastResultReg;
        }
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.CHOMP);
        bc.emitReg(rd);
        bc.emitReg(targetReg);
        bc.lastResultReg = rd;
    }

    private static void visitSprintf(BytecodeCompiler bc, OperatorNode node) {
        if (!(node.operand instanceof ListNode list) || list.elements.isEmpty()) {
            bc.throwCompilerException("sprintf requires a format argument");
            return;
        }
        list.elements.get(0).accept(bc);
        int formatReg = bc.lastResultReg;
        java.util.List<Integer> argRegs = new java.util.ArrayList<>();
        for (int i = 1; i < list.elements.size(); i++) {
            bc.compileNode(list.elements.get(i), -1, RuntimeContextType.LIST);
            argRegs.add(bc.lastResultReg);
        }
        int listReg = bc.allocateRegister();
        bc.emit(Opcodes.CREATE_LIST);
        bc.emitReg(listReg);
        bc.emit(argRegs.size());
        for (int argReg : argRegs) bc.emitReg(argReg);
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.SPRINTF);
        bc.emitReg(rd);
        bc.emitReg(formatReg);
        bc.emitReg(listReg);
        bc.lastResultReg = rd;
    }

    private static void emitSimpleUnary(BytecodeCompiler bc, OperatorNode node, short opcode) {
        node.operand.accept(bc);
        int rs = bc.lastResultReg;
        int rd = bc.allocateOutputRegister();
        bc.emit(opcode);
        bc.emitReg(rd);
        bc.emitReg(rs);
        bc.lastResultReg = rd;
    }

    private static void emitSimpleUnaryScalar(BytecodeCompiler bc, OperatorNode node, short opcode) {
        bc.compileNode(node.operand, -1, RuntimeContextType.SCALAR);
        int rs = bc.lastResultReg;
        int rd = bc.allocateOutputRegister();
        bc.emit(opcode);
        bc.emitReg(rd);
        bc.emitReg(rs);
        bc.lastResultReg = rd;
    }

    private static void visitDieWarn(BytecodeCompiler bc, OperatorNode node, String op) {
        short opcode = op.equals("die") ? Opcodes.DIE : Opcodes.WARN;
        int msgReg;
        if (node.operand != null) {
            node.operand.accept(bc);
            msgReg = bc.lastResultReg;
        } else {
            msgReg = bc.allocateRegister();
            bc.emit(Opcodes.LOAD_UNDEF);
            bc.emitReg(msgReg);
        }
        int locationReg = emitLocationString(bc, node);
        bc.emitWithToken(opcode, node.getIndex());
        bc.emitReg(msgReg);
        bc.emitReg(locationReg);
        if (op.equals("die")) {
            bc.lastResultReg = -1;
        } else {
            int resultReg = bc.allocateRegister();
            bc.emit(Opcodes.LOAD_INT);
            bc.emitReg(resultReg);
            bc.emitInt(1);
            bc.lastResultReg = resultReg;
        }
    }

    /**
     * Handles `defined` operator with special case for `defined *$var`.
     * Perl allows `defined *$var` even under strict refs without auto-vivifying.
     */
    private static void visitDefined(BytecodeCompiler bc, OperatorNode node) {
        // Check for special cases
        if (node.operand instanceof ListNode listNode && listNode.elements.size() == 1) {
            Node operand = listNode.elements.getFirst();
            // Handle defined(+expr) by unwrapping the +
            if (operand instanceof OperatorNode opNode && opNode.operator.equals("+")) {
                operand = opNode.operand;
            }
            if (operand instanceof OperatorNode opNode && opNode.operator.equals("*")) {
                // defined *$var - use special handling that doesn't throw strict refs
                opNode.operand.accept(bc);
                int scalarReg = bc.lastResultReg;
                int pkgIdx = bc.addToStringPool(bc.getCurrentPackage());
                int rd = bc.allocateOutputRegister();
                bc.emit(Opcodes.DEFINED_GLOB);
                bc.emitReg(rd);
                bc.emitReg(scalarReg);
                bc.emit(pkgIdx);
                bc.lastResultReg = rd;
                return;
            }
            // defined(&name) - use stash lookup to match JVM backend/Perl 5 behavior
            if (operand instanceof OperatorNode opNode && opNode.operator.equals("&")
                    && opNode.operand instanceof IdentifierNode idNode) {
                String subName = NameNormalizer.normalizeVariableName(
                        idNode.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(subName);
                int rd = bc.allocateOutputRegister();
                bc.emit(Opcodes.DEFINED_CODE);
                bc.emitReg(rd);
                bc.emit(nameIdx);
                bc.lastResultReg = rd;
                return;
            }
        }
        // Default case: regular defined
        emitSimpleUnary(bc, node, Opcodes.DEFINED);
    }

    private static void visitPopShiftOp(BytecodeCompiler bc, OperatorNode node, short opcode) {
        int arrayReg = resolveArrayOperand(bc, node, node.operator);
        int rd = bc.allocateOutputRegister();
        bc.emit(opcode);
        bc.emitReg(rd);
        bc.emitReg(arrayReg);
        bc.lastResultReg = rd;
    }

    private static void visitSimpleUnaryWithDefault(BytecodeCompiler bc, OperatorNode node, short opcode) {
        compileScalarOperand(bc, node, node.operator);
        int argReg = bc.lastResultReg;
        int rd = bc.allocateOutputRegister();
        bc.emit(opcode);
        bc.emitReg(rd);
        bc.emitReg(argReg);
        bc.lastResultReg = rd;
    }

    private static void visitGenericListOpCase(BytecodeCompiler bc, OperatorNode node, short opcode) {
        int argsReg;
        if (node.operand != null) {
            bc.compileNode(node.operand, -1, RuntimeContextType.LIST);
            int operandReg = bc.lastResultReg;
            argsReg = bc.allocateRegister();
            bc.emit(Opcodes.SCALAR_TO_LIST);
            bc.emitReg(argsReg);
            bc.emitReg(operandReg);
        } else {
            argsReg = bc.allocateRegister();
            bc.emit(Opcodes.CREATE_LIST);
            bc.emitReg(argsReg);
            bc.emit(0);
        }
        int rd = bc.allocateOutputRegister();
        bc.emitWithToken(opcode, node.getIndex());
        bc.emitReg(rd);
        bc.emitReg(argsReg);
        bc.emit(bc.currentCallContext);
        bc.lastResultReg = rd;
    }

    private static void visitFileTestOp(BytecodeCompiler bc, OperatorNode node, String op) {
        boolean isUnderscoreOperand = (node.operand instanceof IdentifierNode)
                && ((IdentifierNode) node.operand).name.equals("_");
        if (isUnderscoreOperand) {
            int rd = bc.allocateOutputRegister();
            int operatorStrIndex = bc.addToStringPool(op);
            bc.emit(Opcodes.FILETEST_LASTHANDLE);
            bc.emitReg(rd);
            bc.emit(operatorStrIndex);
            bc.lastResultReg = rd;
        } else {
            bc.compileNode(node.operand, -1, RuntimeContextType.SCALAR);
            int operandReg = bc.lastResultReg;
            int rd = bc.allocateOutputRegister();
            char testChar = op.charAt(1);
            short opcode = switch (testChar) {
                case 'r' -> Opcodes.FILETEST_R;
                case 'w' -> Opcodes.FILETEST_W;
                case 'x' -> Opcodes.FILETEST_X;
                case 'o' -> Opcodes.FILETEST_O;
                case 'R' -> Opcodes.FILETEST_R_REAL;
                case 'W' -> Opcodes.FILETEST_W_REAL;
                case 'X' -> Opcodes.FILETEST_X_REAL;
                case 'O' -> Opcodes.FILETEST_O_REAL;
                case 'e' -> Opcodes.FILETEST_E;
                case 'z' -> Opcodes.FILETEST_Z;
                case 's' -> Opcodes.FILETEST_S;
                case 'f' -> Opcodes.FILETEST_F;
                case 'd' -> Opcodes.FILETEST_D;
                case 'l' -> Opcodes.FILETEST_L;
                case 'p' -> Opcodes.FILETEST_P;
                case 'S' -> Opcodes.FILETEST_S_UPPER;
                case 'b' -> Opcodes.FILETEST_B;
                case 'c' -> Opcodes.FILETEST_C;
                case 't' -> Opcodes.FILETEST_T;
                case 'u' -> Opcodes.FILETEST_U;
                case 'g' -> Opcodes.FILETEST_G;
                case 'k' -> Opcodes.FILETEST_K;
                case 'T' -> Opcodes.FILETEST_T_UPPER;
                case 'B' -> Opcodes.FILETEST_B_UPPER;
                case 'M' -> Opcodes.FILETEST_M;
                case 'A' -> Opcodes.FILETEST_A;
                case 'C' -> Opcodes.FILETEST_C_UPPER;
                default -> {
                    bc.throwCompilerException("Unsupported file test operator: " + op);
                    yield -1;
                }
            };
            bc.emit(opcode);
            bc.emitReg(rd);
            bc.emitReg(operandReg);
            bc.lastResultReg = rd;
        }
    }

    private static void visitIncrDecr(BytecodeCompiler bc, OperatorNode node, String op) {
        boolean isPostfix = op.endsWith("postfix");
        boolean isIncrement = op.startsWith("++");
        if (node.operand == null) {
            bc.throwCompilerException("Increment/decrement operator requires operand");
            return;
        }
        if (node.operand instanceof IdentifierNode) {
            String varName = ((IdentifierNode) node.operand).name;
            if (bc.hasVariable(varName)) {
                int varReg = bc.getVariableRegister(varName);
                if (isPostfix) {
                    int resultReg = bc.allocateRegister();
                    bc.emit(isIncrement ? Opcodes.POST_AUTOINCREMENT : Opcodes.POST_AUTODECREMENT);
                    bc.emitReg(resultReg);
                    bc.emitReg(varReg);
                    bc.lastResultReg = resultReg;
                } else {
                    bc.emit(isIncrement ? Opcodes.PRE_AUTOINCREMENT : Opcodes.PRE_AUTODECREMENT);
                    bc.emitReg(varReg);
                    bc.lastResultReg = varReg;
                }
                return;
            }
        }
        node.operand.accept(bc);
        int operandReg = bc.lastResultReg;
        if (isPostfix) {
            int resultReg = bc.allocateRegister();
            bc.emit(isIncrement ? Opcodes.POST_AUTOINCREMENT : Opcodes.POST_AUTODECREMENT);
            bc.emitReg(resultReg);
            bc.emitReg(operandReg);
            bc.lastResultReg = resultReg;
        } else {
            bc.emit(isIncrement ? Opcodes.PRE_AUTOINCREMENT : Opcodes.PRE_AUTODECREMENT);
            bc.emitReg(operandReg);
            bc.lastResultReg = operandReg;
        }
    }

    public static void visitOperator(BytecodeCompiler bytecodeCompiler, OperatorNode node) {
        bytecodeCompiler.currentTokenIndex = node.getIndex();
        String op = node.operator;

        switch (op) {
            // Variable declarations and references
            case "my", "our", "local", "state" -> { bytecodeCompiler.compileVariableDeclaration(node, op); return; }
            case "$", "@", "%", "*", "&", "\\" -> { bytecodeCompiler.compileVariableReference(node, op); return; }

            // Simple unary ops (dispatchOperator)
            case "not", "!" -> emitSimpleUnaryScalar(bytecodeCompiler, node, Opcodes.NOT);
            case "~" -> emitSimpleUnary(bytecodeCompiler, node,
                    bytecodeCompiler.isIntegerEnabled() ? Opcodes.INTEGER_BITWISE_NOT : Opcodes.BITWISE_NOT);
            case "binary~" -> emitSimpleUnary(bytecodeCompiler, node, Opcodes.BITWISE_NOT_BINARY);
            case "~." -> emitSimpleUnary(bytecodeCompiler, node, Opcodes.BITWISE_NOT_STRING);
            case "defined" -> visitDefined(bytecodeCompiler, node);
            case "wantarray" -> { int rd = bytecodeCompiler.allocateOutputRegister(); bytecodeCompiler.emit(Opcodes.WANTARRAY); bytecodeCompiler.emitReg(rd); bytecodeCompiler.emitReg(2); bytecodeCompiler.lastResultReg = rd; }
            case "time" -> { int rd = bytecodeCompiler.allocateOutputRegister(); bytecodeCompiler.emit(Opcodes.TIME_OP); bytecodeCompiler.emitReg(rd); bytecodeCompiler.lastResultReg = rd; }
            case "wait" -> { int rd = bytecodeCompiler.allocateOutputRegister(); bytecodeCompiler.emit(Opcodes.WAIT_OP); bytecodeCompiler.emitReg(rd); bytecodeCompiler.lastResultReg = rd; }
            case "getppid" -> { int rd = bytecodeCompiler.allocateOutputRegister(); bytecodeCompiler.emitWithToken(Opcodes.GETPPID, node.getIndex()); bytecodeCompiler.emitReg(rd); bytecodeCompiler.lastResultReg = rd; }
            case "open" -> visitOpen(bytecodeCompiler, node);
            case "matchRegex" -> visitMatchRegex(bytecodeCompiler, node);
            case "replaceRegex" -> visitReplaceRegex(bytecodeCompiler, node);
            case "substr" -> visitSubstr(bytecodeCompiler, node);
            case "chomp" -> visitChomp(bytecodeCompiler, node);
            case "sprintf" -> visitSprintf(bytecodeCompiler, node);
            case "exists" -> CompileExistsDelete.visitExists(bytecodeCompiler, node);
            case "delete" -> CompileExistsDelete.visitDelete(bytecodeCompiler, node);
            case "delete_local" -> CompileExistsDelete.visitDeleteLocal(bytecodeCompiler, node);
            case "die", "warn" -> visitDieWarn(bytecodeCompiler, node, op);

            // Pop/shift
            case "pop" -> visitPopShiftOp(bytecodeCompiler, node, Opcodes.ARRAY_POP);
            case "shift" -> visitPopShiftOp(bytecodeCompiler, node, Opcodes.ARRAY_SHIFT);

            // Simple unary ops with default operand
            case "int" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.INT);
            case "log" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.LOG);
            case "sqrt" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.SQRT);
            case "cos" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.COS);
            case "sin" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.SIN);
            case "exp" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.EXP);
            case "abs" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.ABS);
            case "integerBitwiseNot" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.INTEGER_BITWISE_NOT);
            case "ord" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, bytecodeCompiler.isBytesEnabled() ? Opcodes.ORD_BYTES : Opcodes.ORD);
            case "ordBytes" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.ORD_BYTES);
            case "oct" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.OCT);
            case "hex" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.HEX);
            case "srand" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.SRAND);
            case "chr" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, bytecodeCompiler.isBytesEnabled() ? Opcodes.CHR_BYTES : Opcodes.CHR);
            case "chrBytes" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.CHR_BYTES);
            case "lengthBytes" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.LENGTH_BYTES);
            case "quotemeta" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.QUOTEMETA);
            case "fc" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, bytecodeCompiler.isBytesEnabled() ? Opcodes.FC_BYTES : Opcodes.FC);
            case "lc" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, bytecodeCompiler.isBytesEnabled() ? Opcodes.LC_BYTES : Opcodes.LC);
            case "lcfirst" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, bytecodeCompiler.isBytesEnabled() ? Opcodes.LCFIRST_BYTES : Opcodes.LCFIRST);
            case "uc" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, bytecodeCompiler.isBytesEnabled() ? Opcodes.UC_BYTES : Opcodes.UC);
            case "ucfirst" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, bytecodeCompiler.isBytesEnabled() ? Opcodes.UCFIRST_BYTES : Opcodes.UCFIRST);
            case "tell" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.TELL);
            case "rmdir" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.RMDIR);
            case "closedir" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.CLOSEDIR);
            case "rewinddir" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.REWINDDIR);
            case "telldir" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.TELLDIR);
            case "chdir" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.CHDIR);
            case "exit" -> visitSimpleUnaryWithDefault(bytecodeCompiler, node, Opcodes.EXIT);

            // Generic list ops
            case "chmod" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.CHMOD);
            case "unlink" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.UNLINK);
            case "utime" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.UTIME);
            case "rename" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.RENAME);
            case "link" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.LINK);
            case "readlink" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.READLINK);
            case "umask" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.UMASK);
            case "getc" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETC);
            case "fileno" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.FILENO);
            case "qx" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.QX);
            case "system" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SYSTEM);
            case "kill" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.KILL);
            case "gethostbyname" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETHOSTBYNAME);
            case "caller" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.CALLER);
            case "pack" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.PACK);
            case "unpack" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.UNPACK);
            case "vec" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.VEC);
            case "localtime" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.LOCALTIME);
            case "gmtime" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GMTIME);
            case "reset" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.RESET);
            case "times" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.TIMES);
            case "crypt" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.CRYPT);
            case "close" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.CLOSE);
            case "binmode" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.BINMODE);
            case "seek" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SEEK);
            case "eof" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.EOF_OP);
            case "sysread" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SYSREAD);
            case "syswrite" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SYSWRITE);
            case "sysopen" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SYSOPEN);
            case "socket" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SOCKET);
            case "bind" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.BIND);
            case "connect" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.CONNECT);
            case "listen" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.LISTEN);
            case "pipe" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.PIPE);
            case "socketpair" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SOCKETPAIR);
            case "write" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.WRITE);
            case "formline" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.FORMLINE);
            case "printf" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.PRINTF);
            case "accept" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.ACCEPT);
            case "sysseek" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SYSSEEK);
            case "truncate" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.TRUNCATE);
            case "flock" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.FLOCK);
            case "syscall" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SYSCALL);
            case "read" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.READ);
            case "chown" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.CHOWN);
            case "waitpid" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.WAITPID);
            case "setsockopt" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SETSOCKOPT);
            case "getsockopt" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETSOCKOPT);
            case "getpgrp" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETPGRP);
            case "setpgrp" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SETPGRP);
            case "getpriority" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETPRIORITY);
            case "setpriority" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SETPRIORITY);
            case "symlink" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SYMLINK);
            case "chroot" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.CHROOT);
            case "mkdir" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.MKDIR);
            case "semctl" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SEMCTL);
            case "semget" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SEMGET);
            case "semop" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SEMOP);
            case "msgget" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.MSGGET);
            case "msgsnd" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.MSGSND);
            case "msgrcv" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.MSGRCV);
            case "msgctl" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.MSGCTL);
            case "shmget" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SHMGET);
            case "shmread" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SHMREAD);
            case "shmwrite" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SHMWRITE);
            case "shmctl" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SHMCTL);
            case "exec" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.EXEC);
            case "fcntl" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.FCNTL);
            case "ioctl" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.IOCTL);
            case "getpwent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETPWENT);
            case "setpwent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SETPWENT);
            case "endpwent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.ENDPWENT);
            case "getlogin" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETLOGIN);
            case "getpwnam" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETPWNAM);
            case "getpwuid" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETPWUID);
            case "getgrnam" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETGRNAM);
            case "getgrgid" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETGRGID);
            case "getgrent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETGRENT);
            case "setgrent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SETGRENT);
            case "endgrent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.ENDGRENT);
            case "gethostbyaddr" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETHOSTBYADDR);
            case "getservbyname" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETSERVBYNAME);
            case "getservbyport" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETSERVBYPORT);
            case "getprotobyname" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETPROTOBYNAME);
            case "getprotobynumber" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETPROTOBYNUMBER);
            case "endhostent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.ENDHOSTENT);
            case "endnetent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.ENDNETENT);
            case "endprotoent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.ENDPROTOENT);
            case "endservent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.ENDSERVENT);
            case "gethostent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETHOSTENT);
            case "getnetbyaddr" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETNETBYADDR);
            case "getnetbyname" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETNETBYNAME);
            case "getnetent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETNETENT);
            case "getprotoent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETPROTOENT);
            case "getservent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.GETSERVENT);
            case "sethostent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SETHOSTENT);
            case "setnetent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SETNETENT);
            case "setprotoent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SETPROTOENT);
            case "setservent" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SETSERVENT);
            case "opendir" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.OPENDIR);
            case "readdir" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.READDIR);
            case "seekdir" -> visitGenericListOpCase(bytecodeCompiler, node, Opcodes.SEEKDIR);

            // File test operators
            case "-r", "-w", "-x", "-o", "-R", "-W", "-X", "-O", "-e", "-z", "-s", "-f", "-d", "-l",
                 "-p", "-S", "-b", "-c", "-t", "-u", "-g", "-k", "-T", "-B", "-M", "-A", "-C" -> visitFileTestOp(bytecodeCompiler, node, op);

            // Main operators
            case "scalar" -> {
                if (node.operand != null) {
                    bytecodeCompiler.compileNode(node.operand, -1, RuntimeContextType.SCALAR);
                    int operandReg = bytecodeCompiler.lastResultReg;
                    int rd = bytecodeCompiler.allocateOutputRegister();
                    bytecodeCompiler.emit(Opcodes.ARRAY_SIZE);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(operandReg);
                    bytecodeCompiler.lastResultReg = rd;
                } else {
                    bytecodeCompiler.throwCompilerException("scalar operator requires an operand");
                }
            }
            case "package", "class" -> {
                if (node.operand instanceof IdentifierNode) {
                    String packageName = ((IdentifierNode) node.operand).name;
                    Boolean isClassAnnotation = (Boolean) node.getAnnotation("isClass");
                    boolean isClass = op.equals("class") || (isClassAnnotation != null && isClassAnnotation);
                    String version = bytecodeCompiler.symbolTable.getPackageVersion(packageName);
                    if (version != null) {
                        String versionVarName = packageName + "::VERSION";
                        GlobalVariable.getGlobalVariable(versionVarName).set(new RuntimeScalar(version));
                    }
                    bytecodeCompiler.symbolTable.setCurrentPackage(packageName, isClass);
                    if (isClass) ClassRegistry.registerClass(packageName);
                    boolean isScoped = Boolean.TRUE.equals(node.getAnnotation("isScoped"));
                    int nameIdx = bytecodeCompiler.addToStringPool(packageName);
                    bytecodeCompiler.emit(isScoped ? Opcodes.PUSH_PACKAGE : Opcodes.SET_PACKAGE);
                    bytecodeCompiler.emit(nameIdx);
                    bytecodeCompiler.lastResultReg = -1;
                } else {
                    bytecodeCompiler.throwCompilerException(op + " operator requires an identifier");
                }
            }
            case "say", "print" -> {
                if (node.operand != null) {
                    node.operand.accept(bytecodeCompiler);
                    int rs = bytecodeCompiler.lastResultReg;
                    bytecodeCompiler.emit(op.equals("say") ? Opcodes.SAY : Opcodes.PRINT);
                    bytecodeCompiler.emitReg(rs);
                }
            }
            case "ref" -> {
                if (node.operand == null) {
                    bytecodeCompiler.throwCompilerException("ref requires an argument");
                }
                if (node.operand instanceof ListNode list) {
                    if (list.elements.isEmpty()) bytecodeCompiler.throwCompilerException("ref requires an argument");
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    node.operand.accept(bytecodeCompiler);
                }
                int argReg = bytecodeCompiler.lastResultReg;
                int rd = bytecodeCompiler.allocateOutputRegister();
                bytecodeCompiler.emit(Opcodes.REF);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(argReg);
                bytecodeCompiler.lastResultReg = rd;
            }
            case "prototype" -> {
                if (node.operand == null) {
                    bytecodeCompiler.throwCompilerException("prototype requires an argument");
                }
                if (node.operand instanceof ListNode list) {
                    if (list.elements.isEmpty()) bytecodeCompiler.throwCompilerException("prototype requires an argument");
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    node.operand.accept(bytecodeCompiler);
                }
                int argReg = bytecodeCompiler.lastResultReg;
                int rd = bytecodeCompiler.allocateOutputRegister();
                int packageIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());
                bytecodeCompiler.emit(Opcodes.PROTOTYPE);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(argReg);
                bytecodeCompiler.emitInt(packageIdx);
                bytecodeCompiler.lastResultReg = rd;
            }
            case "quoteRegex" -> {
                if (node.operand == null || !(node.operand instanceof ListNode)) {
                    bytecodeCompiler.throwCompilerException("quoteRegex requires pattern and flags");
                }
                ListNode operand = (ListNode) node.operand;
                if (operand.elements.size() < 2) {
                    bytecodeCompiler.throwCompilerException("quoteRegex requires pattern and flags");
                }
                boolean hasOModifier = false;
                Node flagsNode = operand.elements.get(1);
                if (flagsNode instanceof StringNode) {
                    hasOModifier = ((StringNode) flagsNode).value.contains("o");
                }
                operand.elements.get(0).accept(bytecodeCompiler);
                int patternReg = bytecodeCompiler.lastResultReg;
                flagsNode.accept(bytecodeCompiler);
                int flagsReg = bytecodeCompiler.lastResultReg;
                int rd = bytecodeCompiler.allocateOutputRegister();
                if (hasOModifier) {
                    int callsiteId = bytecodeCompiler.allocateCallsiteId();
                    bytecodeCompiler.emit(Opcodes.QUOTE_REGEX_O);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(patternReg);
                    bytecodeCompiler.emitReg(flagsReg);
                    bytecodeCompiler.emitReg(callsiteId);
                } else {
                    bytecodeCompiler.emit(Opcodes.QUOTE_REGEX);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(patternReg);
                    bytecodeCompiler.emitReg(flagsReg);
                }
                bytecodeCompiler.lastResultReg = rd;
            }
            case "++", "--", "++postfix", "--postfix" -> visitIncrDecr(bytecodeCompiler, node, op);
            case "return" -> {
                // Check if we're inside a defer block - return out of defer is prohibited
                bytecodeCompiler.checkNotInDeferBlock(node.getIndex(), "return");
                // In map/grep blocks, explicit return must use RETURN_NONLOCAL
                // so it propagates as a non-local return to the enclosing subroutine.
                // The regular RETURN opcode is used for implicit end-of-block returns.
                short returnOpcode = bytecodeCompiler.isInMapGrepBlock
                        ? Opcodes.RETURN_NONLOCAL : Opcodes.RETURN;
                if (node.operand != null) {
                    node.operand.accept(bytecodeCompiler);
                } else {
                    int undefReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                    bytecodeCompiler.emitReg(undefReg);
                }
                int exprReg = bytecodeCompiler.lastResultReg;

                // Emit scope exit cleanup for all my-scalars, my-hashes, and my-arrays
                // in the subroutine scope (scope 0). Explicit 'return' bypasses the
                // normal scope exit cleanup at block end, so we must do it here.
                // Skip the exprReg (return value register) — SCOPE_EXIT_CLEANUP nulls
                // the register, which would destroy the return value if it's a my-variable.
                java.util.List<Integer> scalarIdxs = bytecodeCompiler.symbolTable.getMyScalarIndicesInScope(0);
                for (int idx : scalarIdxs) {
                    if (idx == exprReg) continue;
                    bytecodeCompiler.emit(Opcodes.SCOPE_EXIT_CLEANUP);
                    bytecodeCompiler.emitReg(idx);
                }
                java.util.List<Integer> hashIdxs = bytecodeCompiler.symbolTable.getMyHashIndicesInScope(0);
                for (int idx : hashIdxs) {
                    bytecodeCompiler.emit(Opcodes.SCOPE_EXIT_CLEANUP_HASH);
                    bytecodeCompiler.emitReg(idx);
                }
                java.util.List<Integer> arrayIdxs = bytecodeCompiler.symbolTable.getMyArrayIndicesInScope(0);
                for (int idx : arrayIdxs) {
                    bytecodeCompiler.emit(Opcodes.SCOPE_EXIT_CLEANUP_ARRAY);
                    bytecodeCompiler.emitReg(idx);
                }

                bytecodeCompiler.emitWithToken(returnOpcode, node.getIndex());
                bytecodeCompiler.emitReg(exprReg);
                bytecodeCompiler.lastResultReg = -1;
            }
            case "last", "next", "redo" -> {
                // Check if we're inside a defer block - control flow out of defer is prohibited
                bytecodeCompiler.checkNotInDeferBlock(node.getIndex(), op);
                bytecodeCompiler.handleLoopControlOperator(node, op);
                bytecodeCompiler.lastResultReg = -1;
            }
            case "rand" -> {
                int rd = bytecodeCompiler.allocateOutputRegister();
                if (node.operand != null) {
                    node.operand.accept(bytecodeCompiler);
                    int maxReg = bytecodeCompiler.lastResultReg;
                    bytecodeCompiler.emit(Opcodes.RAND);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(maxReg);
                } else {
                    int oneReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.LOAD_INT);
                    bytecodeCompiler.emitReg(oneReg);
                    bytecodeCompiler.emitInt(1);
                    bytecodeCompiler.emit(Opcodes.RAND);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(oneReg);
                }
                bytecodeCompiler.lastResultReg = rd;
            }
            case "sleep" -> {
                int rd = bytecodeCompiler.allocateOutputRegister();
                if (node.operand != null) {
                    node.operand.accept(bytecodeCompiler);
                    int secondsReg = bytecodeCompiler.lastResultReg;
                    bytecodeCompiler.emit(Opcodes.SLEEP_OP);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(secondsReg);
                } else {
                    int maxReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.LOAD_INT);
                    bytecodeCompiler.emitReg(maxReg);
                    bytecodeCompiler.emitInt(Integer.MAX_VALUE);
                    bytecodeCompiler.emit(Opcodes.SLEEP_OP);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(maxReg);
                }
                bytecodeCompiler.lastResultReg = rd;
            }
            case "alarm" -> {
                int rd = bytecodeCompiler.allocateOutputRegister();
                if (node.operand != null) {
                    node.operand.accept(bytecodeCompiler);
                    int argReg = bytecodeCompiler.lastResultReg;
                    bytecodeCompiler.emit(Opcodes.ALARM_OP);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(argReg);
                } else {
                    int zeroReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.LOAD_INT);
                    bytecodeCompiler.emitReg(zeroReg);
                    bytecodeCompiler.emitInt(0);
                    bytecodeCompiler.emit(Opcodes.ALARM_OP);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(zeroReg);
                }
                bytecodeCompiler.lastResultReg = rd;
            }
            case "study" -> {
                if (node.operand != null) node.operand.accept(bytecodeCompiler);
                int rd = bytecodeCompiler.allocateOutputRegister();
                bytecodeCompiler.emit(Opcodes.LOAD_INT);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitInt(1);
                bytecodeCompiler.lastResultReg = rd;
            }
            case "require" -> {
                bytecodeCompiler.compileNode(node.operand, -1, RuntimeContextType.SCALAR);
                int operandReg = bytecodeCompiler.lastResultReg;
                int rd = bytecodeCompiler.allocateOutputRegister();
                bytecodeCompiler.emit(Opcodes.REQUIRE);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(operandReg);
                bytecodeCompiler.lastResultReg = rd;
            }
            case "pos" -> {
                bytecodeCompiler.compileNode(node.operand, -1, RuntimeContextType.SCALAR);
                int operandReg = bytecodeCompiler.lastResultReg;
                int rd = bytecodeCompiler.allocateOutputRegister();
                bytecodeCompiler.emit(Opcodes.POS);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(operandReg);
                bytecodeCompiler.lastResultReg = rd;
            }
            case "index", "rindex" -> {
                if (node.operand instanceof ListNode args) {
                    if (args.elements.isEmpty()) bytecodeCompiler.throwCompilerException("Not enough arguments for " + op);
                    bytecodeCompiler.compileNode(args.elements.get(0), -1, RuntimeContextType.SCALAR);
                    int strReg = bytecodeCompiler.lastResultReg;
                    if (args.elements.size() < 2) bytecodeCompiler.throwCompilerException("Not enough arguments for " + op);
                    bytecodeCompiler.compileNode(args.elements.get(1), -1, RuntimeContextType.SCALAR);
                    int substrReg = bytecodeCompiler.lastResultReg;
                    int posReg;
                    if (args.elements.size() >= 3) {
                        bytecodeCompiler.compileNode(args.elements.get(2), -1, RuntimeContextType.SCALAR);
                        posReg = bytecodeCompiler.lastResultReg;
                    } else {
                        posReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                        bytecodeCompiler.emitReg(posReg);
                    }
                    int rd = bytecodeCompiler.allocateOutputRegister();
                    bytecodeCompiler.emit(op.equals("index") ? Opcodes.INDEX : Opcodes.RINDEX);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(strReg);
                    bytecodeCompiler.emitReg(substrReg);
                    bytecodeCompiler.emitReg(posReg);
                    bytecodeCompiler.lastResultReg = rd;
                } else {
                    bytecodeCompiler.throwCompilerException(op + " requires a list of arguments");
                }
            }
            case "stat", "lstat" -> {
                boolean isUnderscoreOperand = (node.operand instanceof IdentifierNode)
                        && ((IdentifierNode) node.operand).name.equals("_");
                if (isUnderscoreOperand) {
                    int rd = bytecodeCompiler.allocateOutputRegister();
                    bytecodeCompiler.emit(op.equals("stat") ? Opcodes.STAT_LASTHANDLE : Opcodes.LSTAT_LASTHANDLE);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);
                    bytecodeCompiler.lastResultReg = rd;
                } else {
                    int outerContext = bytecodeCompiler.currentCallContext;
                    bytecodeCompiler.compileNode(node.operand, -1, RuntimeContextType.SCALAR);
                    int operandReg = bytecodeCompiler.lastResultReg;
                    int rd = bytecodeCompiler.allocateOutputRegister();
                    bytecodeCompiler.emit(op.equals("stat") ? Opcodes.STAT : Opcodes.LSTAT);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(operandReg);
                    bytecodeCompiler.emit(outerContext);
                    bytecodeCompiler.lastResultReg = rd;
                }
            }
            case "eval", "evalbytes" -> {
                if (node.operand != null) {
                    node.operand.accept(bytecodeCompiler);
                    int stringReg = bytecodeCompiler.lastResultReg;
                    int rd = bytecodeCompiler.allocateOutputRegister();
                    int evalSiteIndex = bytecodeCompiler.evalSiteRegistries.size();
                    bytecodeCompiler.evalSiteRegistries.add(bytecodeCompiler.symbolTable.getVisibleVariableRegistry());
                    bytecodeCompiler.evalSitePragmaFlags.add(new int[]{
                            bytecodeCompiler.symbolTable.strictOptionsStack.peek(),
                            bytecodeCompiler.symbolTable.featureFlagsStack.peek()
                    });
                    bytecodeCompiler.emitWithToken(Opcodes.EVAL_STRING, node.getIndex());
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(stringReg);
                    bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);
                    bytecodeCompiler.emit(evalSiteIndex);
                    bytecodeCompiler.lastResultReg = rd;
                } else {
                    int undefReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                    bytecodeCompiler.emitReg(undefReg);
                    bytecodeCompiler.lastResultReg = undefReg;
                }
            }
            case "select" -> {
                int rd = bytecodeCompiler.allocateOutputRegister();
                boolean hasArgs = node.operand instanceof ListNode ln && !ln.elements.isEmpty();
                if (hasArgs) {
                    bytecodeCompiler.compileNode(node.operand, -1, RuntimeContextType.LIST);
                    int listReg = bytecodeCompiler.lastResultReg;
                    bytecodeCompiler.emitWithToken(Opcodes.SELECT, node.getIndex());
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(listReg);
                } else {
                    int listReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.CREATE_LIST);
                    bytecodeCompiler.emitReg(listReg);
                    bytecodeCompiler.emit(0);
                    bytecodeCompiler.emitWithToken(Opcodes.SELECT, node.getIndex());
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(listReg);
                }
                bytecodeCompiler.lastResultReg = rd;
            }
            case "undef" -> {
                if (node.operand != null) {
                    node.operand.accept(bytecodeCompiler);
                    int operandReg = bytecodeCompiler.lastResultReg;
                    bytecodeCompiler.emit(Opcodes.UNDEFINE_SCALAR);
                    bytecodeCompiler.emitReg(operandReg);
                }
                int undefReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                bytecodeCompiler.emitReg(undefReg);
                bytecodeCompiler.lastResultReg = undefReg;
            }
            case "unaryMinus" -> {
                bytecodeCompiler.compileNode(node.operand, -1, RuntimeContextType.SCALAR);
                int operandReg = bytecodeCompiler.lastResultReg;
                int rd = bytecodeCompiler.allocateOutputRegister();
                bytecodeCompiler.emit(bytecodeCompiler.isNoOverloadingEnabled() ? Opcodes.NEG_NO_OVERLOAD : Opcodes.NEG_SCALAR);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(operandReg);
                bytecodeCompiler.lastResultReg = rd;
            }
            case "splice" -> visitSplice(bytecodeCompiler, node);
            case "reverse" -> visitReverse(bytecodeCompiler, node);
            case "keys" -> visitKeys(bytecodeCompiler, node);
            case "chop" -> visitChop(bytecodeCompiler, node);
            case "values" -> visitValues(bytecodeCompiler, node);
            case "$#" -> visitArrayLastIndex(bytecodeCompiler, node);
            case "length" -> visitLength(bytecodeCompiler, node);
            case "<>" -> visitDiamond(bytecodeCompiler, node);
            case "+" -> { if (node.operand != null) node.operand.accept(bytecodeCompiler); else bytecodeCompiler.throwCompilerException("unary + operator requires an operand"); }
            case "tr", "y" -> visitTransliterate(bytecodeCompiler, node);
            case "tie", "untie", "tied" -> visitTie(bytecodeCompiler, node, op);
            case "atan2" -> visitAtan2(bytecodeCompiler, node);
            case "each" -> visitEach(bytecodeCompiler, node);
            case "glob" -> visitGlob(bytecodeCompiler, node);
            case "doFile" -> visitDoFile(bytecodeCompiler, node);
            case "goto" -> visitGoto(bytecodeCompiler, node);
            case "__SUB__" -> {
                int rd = bytecodeCompiler.allocateOutputRegister();
                int nameIdx = bytecodeCompiler.addToStringPool("__SUB__");
                bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_CODE);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emit(nameIdx);
                bytecodeCompiler.lastResultReg = rd;
            }
            default -> bytecodeCompiler.throwCompilerException("Unsupported operator: " + op);
        }
    }

    private static void visitSplice(BytecodeCompiler bc, OperatorNode node) {
        if (node.operand == null || !(node.operand instanceof ListNode)) bc.throwCompilerException("splice requires array and arguments");
        ListNode list = (ListNode) node.operand;
        if (list.elements.isEmpty() || !(list.elements.get(0) instanceof OperatorNode)) bc.throwCompilerException("splice requires array variable");
        OperatorNode arrayOp = (OperatorNode) list.elements.get(0);
        if (!arrayOp.operator.equals("@")) bc.throwCompilerException("splice requires array variable: splice @array, ...");
        int arrayReg = resolveArrayOperand(bc, new OperatorNode("splice", arrayOp, node.tokenIndex), "splice");
        List<Integer> argRegs = new ArrayList<>();
        // Compile splice arguments in LIST context so replacement values
        // (after offset and length) are properly flattened.
        int savedCtx = bc.currentCallContext;
        bc.currentCallContext = RuntimeContextType.LIST;
        for (int i = 1; i < list.elements.size(); i++) { list.elements.get(i).accept(bc); argRegs.add(bc.lastResultReg); }
        bc.currentCallContext = savedCtx;
        int argsListReg = bc.allocateRegister();
        bc.emit(Opcodes.CREATE_LIST); bc.emitReg(argsListReg); bc.emit(argRegs.size());
        for (int argReg : argRegs) bc.emitReg(argReg);
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.SPLICE); bc.emitReg(rd); bc.emitReg(arrayReg); bc.emitReg(argsListReg); bc.emit(savedCtx);
        bc.lastResultReg = rd;
    }

    private static void visitReverse(BytecodeCompiler bc, OperatorNode node) {
        if (node.operand == null || !(node.operand instanceof ListNode)) bc.throwCompilerException("reverse requires arguments");
        ListNode list = (ListNode) node.operand;
        List<Integer> argRegs = new ArrayList<>();
        for (Node arg : list.elements) { arg.accept(bc); argRegs.add(bc.lastResultReg); }
        int argsListReg = bc.allocateRegister();
        bc.emit(Opcodes.CREATE_LIST); bc.emitReg(argsListReg); bc.emit(argRegs.size());
        for (int argReg : argRegs) bc.emitReg(argReg);
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.REVERSE); bc.emitReg(rd); bc.emitReg(argsListReg); bc.emit(bc.currentCallContext);
        bc.lastResultReg = rd;
    }

    private static void visitKeys(BytecodeCompiler bc, OperatorNode node) {
        if (node.operand == null) bc.throwCompilerException("keys requires a hash argument");
        bc.compileNode(node.operand, -1, RuntimeContextType.LIST);
        int hashReg = bc.lastResultReg;
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.HASH_KEYS); bc.emitReg(rd); bc.emitReg(hashReg);
        if (bc.currentCallContext == RuntimeContextType.SCALAR) {
            int scalarReg = bc.allocateRegister();
            bc.emit(Opcodes.ARRAY_SIZE); bc.emitReg(scalarReg); bc.emitReg(rd);
            bc.lastResultReg = scalarReg;
        } else { bc.lastResultReg = rd; }
    }

    private static void visitChop(BytecodeCompiler bc, OperatorNode node) {
        boolean chopNoArgs = node.operand == null || (node.operand instanceof ListNode && ((ListNode) node.operand).elements.isEmpty());
        int scalarReg;
        if (chopNoArgs) {
            scalarReg = loadDefaultUnderscore(bc);
        } else {
            // Compile operand in LIST context so RuntimeList.chop() handles all elements
            bc.compileNode(node.operand, -1, RuntimeContextType.LIST);
            scalarReg = bc.lastResultReg;
        }
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.CHOP); bc.emitReg(rd); bc.emitReg(scalarReg);
        bc.lastResultReg = rd;
    }

    private static void visitValues(BytecodeCompiler bc, OperatorNode node) {
        if (node.operand == null) bc.throwCompilerException("values requires a hash argument");
        bc.compileNode(node.operand, -1, RuntimeContextType.LIST);
        int hashReg = bc.lastResultReg;
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.HASH_VALUES); bc.emitReg(rd); bc.emitReg(hashReg);
        if (bc.currentCallContext == RuntimeContextType.SCALAR) {
            int scalarReg = bc.allocateRegister();
            bc.emit(Opcodes.ARRAY_SIZE); bc.emitReg(scalarReg); bc.emitReg(rd);
            bc.lastResultReg = scalarReg;
        } else { bc.lastResultReg = rd; }
    }

    private static void visitArrayLastIndex(BytecodeCompiler bc, OperatorNode node) {
        if (node.operand == null) bc.throwCompilerException("$# requires an array argument");
        int arrayReg = -1;
        if (node.operand instanceof OperatorNode operandOp) {
            if (operandOp.operator.equals("@") && operandOp.operand instanceof IdentifierNode) {
                String varName = "@" + ((IdentifierNode) operandOp.operand).name;
                if (bc.hasVariable(varName)) arrayReg = bc.getVariableRegister(varName);
                else {
                    arrayReg = bc.allocateRegister();
                    int nameIdx = bc.addToStringPool(NameNormalizer.normalizeVariableName(((IdentifierNode) operandOp.operand).name, bc.getCurrentPackage()));
                    bc.emit(Opcodes.LOAD_GLOBAL_ARRAY); bc.emitReg(arrayReg); bc.emit(nameIdx);
                }
            } else if (operandOp.operator.equals("$")) {
                operandOp.accept(bc); int refReg = bc.lastResultReg;
                arrayReg = bc.allocateRegister();
                if (bc.isStrictRefsEnabled()) { bc.emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex()); bc.emitReg(arrayReg); bc.emitReg(refReg); }
                else { int pkgIdx = bc.addToStringPool(bc.getCurrentPackage()); bc.emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, node.getIndex()); bc.emitReg(arrayReg); bc.emitReg(refReg); bc.emit(pkgIdx); }
            } else bc.throwCompilerException("$# requires array variable or dereferenced array");
        } else if (node.operand instanceof IdentifierNode) {
            String varName = "@" + ((IdentifierNode) node.operand).name;
            if (bc.hasVariable(varName)) arrayReg = bc.getVariableRegister(varName);
            else {
                arrayReg = bc.allocateRegister();
                int nameIdx = bc.addToStringPool(NameNormalizer.normalizeVariableName(((IdentifierNode) node.operand).name, bc.getCurrentPackage()));
                bc.emit(Opcodes.LOAD_GLOBAL_ARRAY); bc.emitReg(arrayReg); bc.emit(nameIdx);
            }
        } else if (node.operand instanceof BlockNode blockNode) {
            // $#{BLOCK} - evaluate block to get array reference, then get last index
            int savedContext = bc.currentCallContext;
            bc.currentCallContext = RuntimeContextType.SCALAR;
            blockNode.accept(bc);
            bc.currentCallContext = savedContext;
            int refReg = bc.lastResultReg;
            arrayReg = bc.allocateRegister();
            if (bc.isStrictRefsEnabled()) { bc.emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex()); bc.emitReg(arrayReg); bc.emitReg(refReg); }
            else { int pkgIdx = bc.addToStringPool(bc.getCurrentPackage()); bc.emitWithToken(Opcodes.DEREF_ARRAY_NONSTRICT, node.getIndex()); bc.emitReg(arrayReg); bc.emitReg(refReg); bc.emit(pkgIdx); }
        } else bc.throwCompilerException("$# requires array variable");
        int sizeReg = bc.allocateRegister(); bc.emit(Opcodes.ARRAY_SIZE); bc.emitReg(sizeReg); bc.emitReg(arrayReg);
        int oneReg = bc.allocateRegister(); bc.emit(Opcodes.LOAD_INT); bc.emitReg(oneReg); bc.emitInt(1);
        int rd = bc.allocateOutputRegister(); bc.emit(Opcodes.SUB_SCALAR); bc.emitReg(rd); bc.emitReg(sizeReg); bc.emitReg(oneReg);
        bc.lastResultReg = rd;
    }

    private static void visitLength(BytecodeCompiler bc, OperatorNode node) {
        if (node.operand == null) bc.throwCompilerException("length requires an argument");
        if (node.operand instanceof ListNode list) { if (list.elements.isEmpty()) bc.throwCompilerException("length requires an argument"); list.elements.get(0).accept(bc); }
        else node.operand.accept(bc);
        int stringReg = bc.lastResultReg;
        int rd = bc.allocateOutputRegister(); bc.emit(bc.isBytesEnabled() ? Opcodes.LENGTH_BYTES : Opcodes.LENGTH_OP); bc.emitReg(rd); bc.emitReg(stringReg);
        bc.lastResultReg = rd;
    }

    private static void visitDiamond(BytecodeCompiler bc, OperatorNode node) {
        // Determine whether this is readline (<> or <<>>) or glob (<*.t>, <$var/*.t>).
        // After interpolation, glob patterns may produce non-StringNode operands
        // (e.g., BinaryOperatorNode for concatenation like $var . "/*.t").
        boolean isReadline = false;
        if (node.operand instanceof ListNode listNode) {
            if (listNode.elements.isEmpty()) {
                isReadline = true;
            } else if (listNode.elements.getFirst() instanceof StringNode stringNode) {
                isReadline = stringNode.value.isEmpty() || stringNode.value.equals("<>");
            }
            // If element is not a StringNode, it's an interpolated glob pattern → NOT readline
        }
        if (isReadline) {
            bc.compileNode(node.operand, -1, RuntimeContextType.SCALAR);
            int fhReg = bc.lastResultReg;
            int rd = bc.allocateOutputRegister();
            bc.emit(Opcodes.READLINE); bc.emitReg(rd); bc.emitReg(fhReg); bc.emit(bc.currentCallContext);
            bc.lastResultReg = rd;
        } else {
            OperatorNode globNode = new OperatorNode("glob", node.operand, node.tokenIndex);
            globNode.id = node.id; globNode.annotations = node.annotations;
            globNode.accept(bc);
        }
    }

    private static void visitTransliterate(BytecodeCompiler bc, OperatorNode node) {
        if (!(node.operand instanceof ListNode)) bc.throwCompilerException("tr operator requires list operand");
        ListNode list = (ListNode) node.operand;
        if (list.elements.size() < 3) bc.throwCompilerException("tr operator requires search, replace, and modifiers");
        // Compile all elements in SCALAR context (matches JVM backend)
        bc.compileNode(list.elements.get(0), -1, RuntimeContextType.SCALAR); int searchReg = bc.lastResultReg;
        bc.compileNode(list.elements.get(1), -1, RuntimeContextType.SCALAR); int replaceReg = bc.lastResultReg;
        bc.compileNode(list.elements.get(2), -1, RuntimeContextType.SCALAR); int modifiersReg = bc.lastResultReg;
        int targetReg;
        if (list.elements.size() > 3 && list.elements.get(3) != null) {
            // Target like ($y = $x) must be compiled in SCALAR context to get the scalar lvalue, not a list
            bc.compileNode(list.elements.get(3), -1, RuntimeContextType.SCALAR);
            targetReg = bc.lastResultReg;
        } else {
            targetReg = bc.allocateRegister();
            int nameIdx = bc.addToStringPool(NameNormalizer.normalizeVariableName("_", bc.getCurrentPackage()));
            bc.emit(Opcodes.LOAD_GLOBAL_SCALAR);
            bc.emitReg(targetReg);
            bc.emit(nameIdx);
        }
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.TR_TRANSLITERATE); bc.emitReg(rd); bc.emitReg(searchReg); bc.emitReg(replaceReg); bc.emitReg(modifiersReg); bc.emitReg(targetReg); bc.emitInt(bc.currentCallContext);
        bc.lastResultReg = rd;
    }

    private static void visitTie(BytecodeCompiler bc, OperatorNode node, String op) {
        if (node.operand == null) bc.throwCompilerException(op + " requires arguments");
        // Compile operand in LIST context - tie/untie/tied expect a RuntimeList argument
        bc.compileNode(node.operand, -1, RuntimeContextType.LIST); int argsReg = bc.lastResultReg;
        int rd = bc.allocateOutputRegister();
        short opcode = switch (op) { case "tie" -> Opcodes.TIE; case "untie" -> Opcodes.UNTIE; case "tied" -> Opcodes.TIED; default -> throw new IllegalStateException("Unexpected operator: " + op); };
        bc.emitWithToken(opcode, node.getIndex()); bc.emitReg(rd); bc.emitReg(argsReg); bc.emit(bc.currentCallContext);
        bc.lastResultReg = rd;
    }

    private static void visitAtan2(BytecodeCompiler bc, OperatorNode node) {
        if (!(node.operand instanceof ListNode) || ((ListNode) node.operand).elements.size() < 2) bc.throwCompilerException("atan2 requires two arguments");
        ListNode list = (ListNode) node.operand;
        list.elements.get(0).accept(bc); int rs1 = bc.lastResultReg;
        list.elements.get(1).accept(bc); int rs2 = bc.lastResultReg;
        int rd = bc.allocateOutputRegister();
        bc.emitWithToken(Opcodes.ATAN2, node.getIndex()); bc.emitReg(rd); bc.emitReg(rs1); bc.emitReg(rs2);
        bc.lastResultReg = rd;
    }

    private static void visitEach(BytecodeCompiler bc, OperatorNode node) {
        if (node.operand == null) bc.throwCompilerException("each requires an argument");
        bc.compileNode(node.operand, -1, RuntimeContextType.LIST);
        int containerReg = bc.lastResultReg;
        int rd = bc.allocateOutputRegister();
        bc.emitWithToken(Opcodes.EACH, node.getIndex()); bc.emitReg(rd); bc.emitReg(containerReg); bc.emit(bc.currentCallContext);
        bc.lastResultReg = rd;
    }

    private static void visitGlob(BytecodeCompiler bc, OperatorNode node) {
        int globId = ScalarGlobOperator.currentId++;
        bc.compileNode(node.operand, -1, RuntimeContextType.SCALAR);
        int patternReg = bc.lastResultReg;
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.GLOB_OP); bc.emitReg(rd); bc.emit(globId); bc.emitReg(patternReg); bc.emit(bc.currentCallContext);
        bc.lastResultReg = rd;
    }

    private static void visitDoFile(BytecodeCompiler bc, OperatorNode node) {
        bc.compileNode(node.operand, -1, RuntimeContextType.SCALAR);
        int fileReg = bc.lastResultReg;
        int rd = bc.allocateOutputRegister();
        bc.emit(Opcodes.DO_FILE); bc.emitReg(rd); bc.emitReg(fileReg); bc.emit(bc.currentCallContext);
        bc.lastResultReg = rd;
    }

    private static void visitGoto(BytecodeCompiler bc, OperatorNode node) {
        // Check if we're inside a defer block - goto out of defer is prohibited
        bc.checkNotInDeferBlock(node.getIndex(), "goto");
        
        String labelStr = null;
        if (node.operand instanceof ListNode labelNode && !labelNode.elements.isEmpty()) {
            Node arg = labelNode.elements.getFirst();
            
            // Check if this is goto &NAME or goto &{expr} - a subroutine call form
            // The parser produces: BinaryOperatorNode "(" with left=OperatorNode "&" (for &NAME)
            // or left=BlockNode (for &{expr}) and right=args
            if (arg instanceof BinaryOperatorNode callNode && callNode.operator.equals("(")) {
                Node callTarget = callNode.left;

                // Determine eval scope for runtime check (Perl checks undefined sub before eval context)
                String evalScope = bc.getEvalScopeType();
                // Encode eval scope as string pool index (-1 = not in eval)
                int evalScopeIdx = -1;
                if (evalScope != null && (callTarget instanceof OperatorNode opNode2 && opNode2.operator.equals("&")
                        || callTarget instanceof BlockNode
                        || callTarget instanceof OperatorNode opNode3 && opNode3.operator.equals("$"))) {
                    evalScopeIdx = bc.addToStringPool(evalScope);
                }

                if (callTarget instanceof OperatorNode opNode && opNode.operator.equals("&")) {
                    // This is goto &sub - create a TAILCALL marker and return it
                    int outerContext = bc.currentCallContext;
                    bc.compileNode(callTarget, -1, RuntimeContextType.SCALAR);
                    int codeRefReg = bc.lastResultReg;
                    bc.compileNode(callNode.right, -1, RuntimeContextType.LIST);
                    int argsReg = bc.lastResultReg;
                    int rd = bc.allocateOutputRegister();
                    bc.emit(Opcodes.GOTO_TAILCALL);
                    bc.emitReg(rd);
                    bc.emitReg(codeRefReg);
                    bc.emitReg(argsReg);
                    bc.emit(outerContext);
                    bc.emit(evalScopeIdx);
                    bc.emitWithToken(Opcodes.RETURN, node.getIndex());
                    bc.emitReg(rd);
                    bc.lastResultReg = -1;
                    return;
                }
                // Handle goto &{expr} - symbolic code dereference
                // BlockNode contains an expression that evaluates to a subroutine name
                if (callTarget instanceof BlockNode blockNode) {
                    int outerContext = bc.currentCallContext;
                    // Evaluate the block to get the subroutine name/reference
                    bc.compileNode(blockNode, -1, RuntimeContextType.SCALAR);
                    int nameReg = bc.lastResultReg;
                    // Look up the CODE reference using codeDerefNonStrict
                    int codeRefReg = bc.allocateOutputRegister();
                    int pkgIdx = bc.addToStringPool(bc.getCurrentPackage());
                    bc.emit(Opcodes.CODE_DEREF_NONSTRICT);
                    bc.emitReg(codeRefReg);
                    bc.emitReg(nameReg);
                    bc.emit(pkgIdx);
                    // Compile the args
                    bc.compileNode(callNode.right, -1, RuntimeContextType.LIST);
                    int argsReg = bc.lastResultReg;
                    int rd = bc.allocateOutputRegister();
                    bc.emit(Opcodes.GOTO_TAILCALL);
                    bc.emitReg(rd);
                    bc.emitReg(codeRefReg);
                    bc.emitReg(argsReg);
                    bc.emit(outerContext);
                    bc.emit(evalScopeIdx);
                    bc.emitWithToken(Opcodes.RETURN, node.getIndex());
                    bc.emitReg(rd);
                    bc.lastResultReg = -1;
                    return;
                }
                // Handle goto &$sub - variable code dereference
                // The parser transforms &$sub into $sub(@_), so callTarget is OperatorNode("$", ...)
                if (callTarget instanceof OperatorNode opNode && opNode.operator.equals("$")) {
                    int outerContext = bc.currentCallContext;
                    // Evaluate the $sub variable to get the CODE reference
                    bc.compileNode(callTarget, -1, RuntimeContextType.SCALAR);
                    int codeRefReg = bc.lastResultReg;
                    // Compile the args
                    bc.compileNode(callNode.right, -1, RuntimeContextType.LIST);
                    int argsReg = bc.lastResultReg;
                    int rd = bc.allocateOutputRegister();
                    bc.emit(Opcodes.GOTO_TAILCALL);
                    bc.emitReg(rd);
                    bc.emitReg(codeRefReg);
                    bc.emitReg(argsReg);
                    bc.emit(outerContext);
                    bc.emit(evalScopeIdx);
                    bc.emitWithToken(Opcodes.RETURN, node.getIndex());
                    bc.emitReg(rd);
                    bc.lastResultReg = -1;
                    return;
                }
            }
            
            if (arg instanceof IdentifierNode) labelStr = ((IdentifierNode) arg).name;
            else {
                // Dynamic goto (goto EXPR) - evaluate expression at runtime
                // Check if EXPR could be a CODE reference (goto &sub handled above)
                bc.compileNode(arg, -1, RuntimeContextType.SCALAR);
                int exprReg = bc.lastResultReg;
                bc.emit(Opcodes.GOTO_DYNAMIC);
                bc.emit(exprReg);
                bc.lastResultReg = -1;
                return;
            }
        }
        if (labelStr == null) {
            // Bare `goto` without args: emit GOTO_DYNAMIC with empty string → runtime error
            int rd = bc.allocateOutputRegister();
            int emptyIdx = bc.addToStringPool("");
            bc.emit(Opcodes.LOAD_STRING);
            bc.emitReg(rd);
            bc.emit(emptyIdx);
            bc.emit(Opcodes.GOTO_DYNAMIC);
            bc.emit(rd);
            bc.lastResultReg = -1;
            return;
        }
        Integer targetPc = bc.gotoLabelPcs.get(labelStr);
        if (targetPc != null) { bc.emit(Opcodes.GOTO); bc.emitInt(targetPc); }
        else {
            // Label not yet seen - use GOTO_DYNAMIC which checks code.gotoLabelPcs at runtime
            // (populated with all labels after compilation) and creates a GOTO marker for non-local gotos.
            // This handles both forward references within the same scope and non-local gotos to outer scopes.
            int rd = bc.allocateOutputRegister();
            int labelIdx = bc.addToStringPool(labelStr);
            bc.emit(Opcodes.LOAD_STRING);
            bc.emitReg(rd);
            bc.emit(labelIdx);
            bc.emit(Opcodes.GOTO_DYNAMIC);
            bc.emit(rd);
        }
        bc.lastResultReg = -1;
    }
}
