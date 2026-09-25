package org.perlonjava.backend.bytecode;

import org.perlonjava.frontend.analysis.ConstantFoldingVisitor;
import org.perlonjava.frontend.analysis.LValueVisitor;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.semantic.SymbolTable;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.util.ArrayList;
import java.util.List;

public class CompileAssignment {

    /** Emit the runtime warning required by the experimental refaliasing feature. */
    private static void emitReferenceAliasWarning(BytecodeCompiler bc, int tokenIndex) {
        if (!bc.symbolTable.isWarningCategoryEnabled("experimental::refaliasing")) {
            return;
        }
        int messageReg = bc.allocateRegister();
        bc.emit(Opcodes.LOAD_STRING);
        bc.emitReg(messageReg);
        bc.emit(bc.addToStringPool("Aliasing via reference is experimental"));
        int locationReg = bc.allocateRegister();
        bc.emit(Opcodes.LOAD_STRING);
        bc.emitReg(locationReg);
        String location = bc.errorUtil != null ? bc.errorUtil.warningLocation(tokenIndex) : "";
        bc.emit(bc.addToStringPool(location));
        bc.emitWithToken(Opcodes.WARN, tokenIndex);
        bc.emitReg(messageReg);
        bc.emitReg(locationReg);
    }

    private static boolean isLexicalSubStorage(OperatorNode scalar) {
        return scalar.getAnnotation("hiddenVarName") != null
                || scalar.operand instanceof IdentifierNode id && id.name.contains("__lexsub_");
    }

    /** Return Perl's target-specific diagnostic for an invalid ref alias. */
    private static String invalidReferenceAliasTarget(Node target, boolean listAssignment,
                                                      boolean parenthesizedTarget) {
        while (target instanceof ListNode list && list.elements.size() == 1) {
            target = list.elements.getFirst();
        }
        if (target instanceof OperatorNode reference && reference.operator.equals("\\")) {
            return invalidReferenceAliasTarget(reference.operand, listAssignment, parenthesizedTarget);
        }
        String assignment = listAssignment ? "list assignment" : "scalar assignment";
        if (target instanceof BlockNode block
                && Boolean.TRUE.equals(block.getAnnotation("blockIsDoBlock"))) {
            return "Can't modify reference to do block in " + assignment;
        }
        if (target instanceof OperatorNode operator) {
            if (operator.operator.equals("pos")) {
                return "Can't modify reference to match position in " + assignment;
            }
            if (operator.operator.equals("glob")) {
                return "Can't modify reference to glob in " + assignment;
            }
            if (operator.operator.equals("%") && operator.operand instanceof BlockNode) {
                return "Can't modify reference to hash dereference in " + assignment;
            }
            if (operator.operator.equals("%") && parenthesizedTarget) {
                return "Can't modify reference to parenthesized hash in list assignment";
            }
            if ((operator.operator.equals("my") || operator.operator.equals("state"))
                    && operator.operand instanceof ListNode declarationList
                    && declarationList.elements.size() == 1
                    && declarationList.elements.getFirst() instanceof OperatorNode declarationHash
                    && declarationHash.operator.equals("%")) {
                return "Can't modify reference to parenthesized hash in list assignment";
            }
        }
        if (target instanceof BinaryOperatorNode binary && binary.operator.equals("=~")
                && binary.right instanceof OperatorNode transliteration
                && transliteration.operator.equals("tr")) {
            return "Can't modify transliteration (tr///) in scalar assignment";
        }
        return null;
    }

    private static boolean isReferenceAliasRhs(Node rhs) {
        return rhs instanceof OperatorNode operator && operator.operator.equals("\\");
    }

    private static String invalidLocalizedArrayReferenceAlias(Node target, boolean listAssignment) {
        while (target instanceof ListNode list && list.elements.size() == 1) {
            target = list.elements.getFirst();
        }
        while (target instanceof OperatorNode reference && reference.operator.equals("\\")) {
            target = reference.operand;
            while (target instanceof ListNode list && list.elements.size() == 1) {
                target = list.elements.getFirst();
            }
        }
        if (!(target instanceof OperatorNode local) || !local.operator.equals("local")) {
            return null;
        }
        Node localTarget = local.operand;
        while (localTarget instanceof ListNode list && list.elements.size() == 1) {
            localTarget = list.elements.getFirst();
        }
        if (!(localTarget instanceof OperatorNode localArray) || !localArray.operator.equals("@")) {
            return null;
        }
        String assignment = listAssignment ? "list assignment" : "scalar assignment";
        if (localArray.operand instanceof BlockNode) {
            return "Can't modify reference to array dereference in " + assignment;
        }
        return "Can't modify reference to localized parenthesized array in " + assignment;
    }

    private static String invalidReferenceAliasTernary(TernaryOperatorNode ternary) {
        for (Node branch : List.of(ternary.trueExpr, ternary.falseExpr)) {
            if (branch instanceof OperatorNode reference && reference.operator.equals("\\")) {
                String diagnostic = invalidReferenceAliasTarget(reference.operand, false, false);
                if (diagnostic != null) return diagnostic;
            }
        }
        return null;
    }

    /**
     * A conditional member of a ref-alias list can choose an aggregate
     * lvalue.  Compiling the ternary as an ordinary LVALUE collapses that
     * aggregate into a scalar temporary, which later makes
     * ALIAS_LVALUE_REFERENCE reject it.  Keep the branch type visible and
     * perform the corresponding alias operation in the selected branch.
     */
    private static boolean compileConditionalReferenceAliasTarget(
            BytecodeCompiler bc, TernaryOperatorNode ternary, int rhsListReg, int index, int tokenIndex) {
        if (!isDirectReferenceAliasTarget(ternary.trueExpr)
                || !isDirectReferenceAliasTarget(ternary.falseExpr)) {
            return false;
        }
        bc.compileNode(ternary.condition, -1, RuntimeContextType.SCALAR);
        int conditionReg = bc.lastResultReg;
        int ifFalsePos = bc.bytecode.size();
        bc.emit(bc.gotoIfFalseOpcode());
        bc.emitReg(conditionReg);
        bc.emitInt(0);

        compileDirectReferenceAliasTarget(bc, ternary.trueExpr, rhsListReg, index, tokenIndex);
        int gotoEndPos = bc.bytecode.size();
        bc.emit(Opcodes.GOTO);
        bc.emitInt(0);

        bc.patchIntOffset(ifFalsePos + 2, bc.bytecode.size());
        compileDirectReferenceAliasTarget(bc, ternary.falseExpr, rhsListReg, index, tokenIndex);
        bc.patchIntOffset(gotoEndPos + 1, bc.bytecode.size());
        return true;
    }

    /**
     * Bind an already-evaluated reference to a ref-alias assignment target.
     *
     * A target may select another target (with a ternary) or explicitly wrap
     * it in {@code \}.  Keeping that selection here avoids treating the
     * ternary as an ordinary value expression: each selected branch is lowered
     * as an lvalue and receives the same reference.
     */
    private static void compileReferenceAliasTarget(
            BytecodeCompiler bc, Node target, int referenceReg, int tokenIndex) {
        compileReferenceAliasTarget(bc, target, referenceReg, tokenIndex, true);
    }

    private static void compileReferenceAliasTarget(
            BytecodeCompiler bc, Node target, int referenceReg, int tokenIndex, boolean aliasBareScalar) {
        if (target instanceof TernaryOperatorNode ternary) {
            bc.compileNode(ternary.condition, -1, RuntimeContextType.SCALAR);
            int conditionReg = bc.lastResultReg;
            int ifFalsePos = bc.bytecode.size();
            bc.emit(bc.gotoIfFalseOpcode());
            bc.emitReg(conditionReg);
            bc.emitInt(0);
            compileReferenceAliasTarget(bc, ternary.trueExpr, referenceReg, tokenIndex, aliasBareScalar);
            int gotoEndPos = bc.bytecode.size();
            bc.emit(Opcodes.GOTO);
            bc.emitInt(0);
            bc.patchIntOffset(ifFalsePos + 2, bc.bytecode.size());
            compileReferenceAliasTarget(bc, ternary.falseExpr, referenceReg, tokenIndex, aliasBareScalar);
            bc.patchIntOffset(gotoEndPos + 1, bc.bytecode.size());
            return;
        }
        if (target instanceof OperatorNode reference && reference.operator.equals("\\")) {
            compileReferenceAliasTarget(bc, reference.operand, referenceReg, tokenIndex, true);
            return;
        }
        String invalidTargetDiagnostic = invalidReferenceAliasTarget(target, false, false);
        if (invalidTargetDiagnostic != null) {
            bc.throwCompilerException(invalidTargetDiagnostic);
            return;
        }
        if (target instanceof OperatorNode scalar
                && scalar.operator.equals("$")
                && scalar.operand instanceof IdentifierNode id) {
            if (!aliasBareScalar) {
                bc.compileNode(target, -1, RuntimeContextType.LVALUE);
                int targetReg = bc.lastResultReg;
                bc.emit(Opcodes.SET_SCALAR);
                bc.emitReg(targetReg);
                bc.emitReg(referenceReg);
                bc.lastResultReg = targetReg;
                return;
            }
            String variableName = "$" + id.name;
            int referentReg = bc.allocateRegister();
            bc.emitWithToken(Opcodes.REFALIAS_SCALAR_REFERENCE, tokenIndex);
            bc.emitReg(referentReg);
            bc.emitReg(referenceReg);
            if (bc.hasVariable(variableName) && !bc.isOurVariable(variableName)) {
                int targetReg = bc.getVariableRegister(variableName);
                bc.emit(Opcodes.ALIAS);
                bc.emitReg(targetReg);
                bc.emitReg(referentReg);
                bc.lastResultReg = targetReg;
            } else {
                int nameIdx = bc.addToStringPool(NameNormalizer.normalizeVariableName(id.name, bc.getCurrentPackage()));
                bc.emit(Opcodes.ALIAS_GLOBAL_SCALAR);
                bc.emit(nameIdx);
                bc.emitReg(referentReg);
                int targetReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                bc.emitReg(targetReg);
                bc.emit(nameIdx);
                bc.lastResultReg = targetReg;
            }
            return;
        }
        bc.compileNode(target, -1, RuntimeContextType.LVALUE);
        int targetReg = bc.lastResultReg;
        bc.emit(Opcodes.ALIAS_LVALUE_REFERENCE);
        bc.emitReg(targetReg);
        bc.emitReg(referenceReg);
        bc.lastResultReg = targetReg;
    }

    private static boolean isDirectReferenceAliasTarget(Node target) {
        return target instanceof OperatorNode sigil
                && (sigil.operator.equals("$") || sigil.operator.equals("@") || sigil.operator.equals("%"))
                && sigil.operand instanceof IdentifierNode;
    }

    /**
     * Compile one conditional scalar/array/hash target and leave its value in
     * lastResultReg.  Unlike a bare {@code @array} member of a ref-alias list,
     * an aggregate selected by a conditional is one lvalue and consumes one
     * aggregate reference (for example {@code $ok ? @left : %right}).
     */
    private static void compileDirectReferenceAliasTarget(
            BytecodeCompiler bc, Node target, int rhsListReg, int index, int tokenIndex) {
        OperatorNode sigil = (OperatorNode) target;
        IdentifierNode id = (IdentifierNode) sigil.operand;
        String variableName = sigil.operator + id.name;
        int indexReg = bc.allocateRegister();
        bc.emit(Opcodes.LOAD_INT);
        bc.emitReg(indexReg);
        bc.emit(index);
        int referenceReg = bc.allocateRegister();
        bc.emit(Opcodes.ARRAY_GET);
        bc.emitReg(referenceReg);
        bc.emitReg(rhsListReg);
        bc.emitReg(indexReg);

        if (sigil.operator.equals("@")) {
            int arrayReg = bc.allocateRegister();
            bc.emitWithToken(Opcodes.FOREACH_DEREF_ARRAY, tokenIndex);
            bc.emitReg(arrayReg);
            bc.emitReg(referenceReg);
            if (bc.hasVariable(variableName) && !bc.isOurVariable(variableName)) {
                int targetReg = bc.getVariableRegister(variableName);
                bc.emit(Opcodes.ALIAS);
                bc.emitReg(targetReg);
                bc.emitReg(arrayReg);
                bc.lastResultReg = targetReg;
            } else {
                int nameIdx = bc.addToStringPool(NameNormalizer.normalizeVariableName(id.name, bc.getCurrentPackage()));
                bc.emit(Opcodes.ALIAS_GLOBAL_ARRAY);
                bc.emit(nameIdx);
                bc.emitReg(arrayReg);
                int targetReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                bc.emitReg(targetReg);
                bc.emit(nameIdx);
                bc.lastResultReg = targetReg;
            }
            return;
        }

        if (sigil.operator.equals("$")) {
            int derefReg = bc.allocateRegister();
            bc.emitWithToken(Opcodes.REFALIAS_SCALAR_REFERENCE, tokenIndex);
            bc.emitReg(derefReg);
            bc.emitReg(referenceReg);
            if (bc.hasVariable(variableName) && !bc.isOurVariable(variableName)) {
                int targetReg = bc.getVariableRegister(variableName);
                bc.emit(Opcodes.ALIAS);
                bc.emitReg(targetReg);
                bc.emitReg(derefReg);
                bc.lastResultReg = targetReg;
            } else {
                int nameIdx = bc.addToStringPool(NameNormalizer.normalizeVariableName(id.name, bc.getCurrentPackage()));
                bc.emit(Opcodes.ALIAS_GLOBAL_SCALAR);
                bc.emit(nameIdx);
                bc.emitReg(derefReg);
                int targetReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                bc.emitReg(targetReg);
                bc.emit(nameIdx);
                bc.lastResultReg = targetReg;
            }
            return;
        }

        int hashReg = bc.allocateRegister();
        bc.emitWithToken(Opcodes.FOREACH_DEREF_HASH, tokenIndex);
        bc.emitReg(hashReg);
        bc.emitReg(referenceReg);
        if (bc.hasVariable(variableName) && !bc.isOurVariable(variableName)) {
            int targetReg = bc.getVariableRegister(variableName);
            bc.emit(Opcodes.ALIAS);
            bc.emitReg(targetReg);
            bc.emitReg(hashReg);
            bc.lastResultReg = targetReg;
        } else {
            int nameIdx = bc.addToStringPool(NameNormalizer.normalizeVariableName(id.name, bc.getCurrentPackage()));
            bc.emit(Opcodes.ALIAS_GLOBAL_HASH);
            bc.emit(nameIdx);
            bc.emitReg(hashReg);
            int targetReg = bc.allocateRegister();
            bc.emit(Opcodes.LOAD_GLOBAL_HASH);
            bc.emitReg(targetReg);
            bc.emit(nameIdx);
            bc.lastResultReg = targetReg;
        }
    }

    /**
     * Resolve the value inside {@code *{EXPR}} to the glob it names.
     * A glob reference must retain its referenced stash slot; only a plain
     * string under {@code no strict 'refs'} is a symbolic glob name.
     */
    private static int emitGlobDeref(
            BytecodeCompiler bc, int valueReg, int tokenIndex) {
        int globReg = bc.allocateRegister();
        int pkgIdx = bc.addToStringPool(bc.getCurrentPackage());
        bc.emitWithToken(
                bc.isStrictRefsEnabled() ? Opcodes.DEREF_GLOB : Opcodes.DEREF_GLOB_NONSTRICT,
                tokenIndex);
        bc.emitReg(globReg);
        bc.emitReg(valueReg);
        bc.emit(pkgIdx);
        return globReg;
    }

    private static boolean compileForwardCodeGlobAlias(
            BytecodeCompiler bc, Node lhs, Node rhs) {
        if (!RuntimeCode.isUseInterpreter()
                || !(lhs instanceof OperatorNode globOp) || !globOp.operator.equals("*")
                || !(rhs instanceof OperatorNode refOp) || !refOp.operator.equals("\\")
                || !(refOp.operand instanceof OperatorNode codeOp) || !codeOp.operator.equals("&")
                || !(codeOp.operand instanceof IdentifierNode idNode)) {
            return false;
        }
        String sourceName = NameNormalizer.normalizeVariableName(idNode.name, bc.getCurrentPackage());
        RuntimeScalar source = GlobalVariable.getGlobalCodeRef(sourceName);
        if (!(source.value instanceof RuntimeCode code) || code.defined()) {
            return false;
        }
        code.requiresForwardGlobAliasGroup = true;
        int valueReg = bc.allocateRegister();
        int nameIdx = bc.addToStringPool(sourceName);
        bc.emit(Opcodes.LOAD_GLOBAL_CODE);
        bc.emitReg(valueReg);
        bc.emit(nameIdx);
        bc.lastResultReg = valueReg;
        return true;
    }

    private static boolean shouldReleaseConsumedRhsTemp(Node rhs) {
        if (rhs instanceof HashLiteralNode || rhs instanceof ArrayLiteralNode) {
            return true;
        }
        if (rhs instanceof BinaryOperatorNode bin) {
            if (bin.operator.equals("(") || bin.operator.equals("()")) {
                return true;
            }
            if (bin.operator.equals("->")
                    && bin.right instanceof BinaryOperatorNode rightCall
                    && (rightCall.operator.equals("(") || rightCall.operator.equals("()"))) {
                return true;
            }
        }
        return false;
    }

    private static void emitReleaseConsumedRhsTemp(BytecodeCompiler bc, Node rhs, int valueReg, int targetReg) {
        if (!shouldReleaseConsumedRhsTemp(rhs)) {
            return;
        }
        bc.emit(Opcodes.RELEASE_CONSUMED_TEMP);
        bc.emitReg(valueReg);
        bc.emitReg(targetReg);
    }

    /** Copy an evaluated scalar RHS before {@code local} clears its source slot. */
    private static int snapshotLocalScalarRhs(BytecodeCompiler bc, int valueReg) {
        int snapshotReg = bc.allocateRegister();
        bc.emit(Opcodes.LOAD_UNDEF);
        bc.emitReg(snapshotReg);
        bc.emit(Opcodes.SET_SCALAR);
        bc.emitReg(snapshotReg);
        bc.emitReg(valueReg);
        return snapshotReg;
    }

    /**
     * A direct array RHS in a localized scalar assignment is consumed as a
     * list.  Other RHS expressions retain scalar context: notably, readline
     * must not become a list read merely because its target is localized.
     */
    private static int compileLocalScalarRhs(BytecodeCompiler bc, Node rhs) {
        if (rhs instanceof OperatorNode operator && operator.operator.equals("@")) {
            bc.compileNode(rhs, -1, RuntimeContextType.LIST);
            int listReg = bc.lastResultReg;
            int scalarReg = bc.allocateRegister();
            // The bytecode register holds a RuntimeArray for a direct array
            // expression.  A localized scalar assignment consumes that array
            // as a list, so select its final element rather than its scalar
            // (element-count) value.
            int lastIndexReg = bc.allocateRegister();
            bc.emit(Opcodes.LOAD_INT);
            bc.emitReg(lastIndexReg);
            bc.emit(-1);
            bc.emit(Opcodes.ARRAY_GET);
            bc.emitReg(scalarReg);
            bc.emitReg(listReg);
            bc.emitReg(lastIndexReg);
            return scalarReg;
        }
        return compileRhs(bc, rhs, RuntimeContextType.SCALAR);
    }

    private static int compileRhs(BytecodeCompiler bc, Node rhs, int context) {
        bc.compileNode(rhs, -1, context);
        return bc.lastResultReg;
    }

    /** Compile whole-array refaliasing for {@code \state @array = ARRAYREF}. */
    private static boolean compileStateArrayReferenceAliasAssignment(
            BytecodeCompiler bc, BinaryOperatorNode node) {
        if (!(node.left instanceof OperatorNode reference) || !reference.operator.equals("\\")
                || !(reference.operand instanceof OperatorNode declaration)
                || !declaration.operator.equals("state")
                || !(declaration.operand instanceof OperatorNode array)
                || !array.operator.equals("@")
                || !(array.operand instanceof IdentifierNode identifier)) {
            return false;
        }
        if (!bc.symbolTable.isFeatureCategoryEnabled("refaliasing")) {
            bc.throwCompilerException("Experimental aliasing via reference not enabled");
            return true;
        }
        emitReferenceAliasWarning(bc, node.getIndex());

        String varName = "@" + identifier.name;
        int persistId = array.id;
        int nameIdx = bc.addToStringPool(varName);
        int arrayReg = bc.allocateStateVariableRegister();
        int initializedReg = bc.allocateRegister();
        bc.emitWithToken(Opcodes.STATE_RETRIEVE_ARRAY, node.getIndex());
        bc.emitReg(arrayReg);
        bc.emit(nameIdx);
        bc.emit(persistId);
        bc.emit(Opcodes.STATE_IS_INITIALIZED);
        bc.emitReg(initializedReg);
        bc.emit(nameIdx);
        bc.emit(persistId);
        bc.emit(bc.gotoIfTrueOpcode());
        bc.emitReg(initializedReg);
        int initializedJump = bc.bytecode.size();
        bc.emitInt(0);

        int referenceReg = compileRhs(bc, node.right, RuntimeContextType.SCALAR);
        int sourceArrayReg = referenceReg;
        if (!(node.right instanceof ArrayLiteralNode)) {
            sourceArrayReg = bc.allocateRegister();
            bc.emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
            bc.emitReg(sourceArrayReg);
            bc.emitReg(referenceReg);
        }
        bc.emit(Opcodes.STATE_ALIAS_ARRAY);
        bc.emitReg(arrayReg);
        bc.emitReg(sourceArrayReg);
        bc.emit(nameIdx);
        bc.emit(persistId);
        bc.patchIntOffset(initializedJump, bc.bytecode.size());
        bc.emitActiveLexicalBinding(arrayReg, varName);
        bc.registerStateArrayVariable(varName, arrayReg, persistId);
        bc.lastResultReg = arrayReg;
        return true;
    }

    /**
     * Compile {@code \@array[indices] = REFERENCES}, including its localized
     * form. A slice is one ref-alias target per index, not one target whose
     * RHS is the aggregate list. This path is also required when a large file
     * falls back from the JVM emitter to the bytecode interpreter.
     */
    private static boolean compileArraySliceReferenceAliasAssignment(
            BytecodeCompiler bc, BinaryOperatorNode node) {
        if (!(node.left instanceof OperatorNode reference) || !reference.operator.equals("\\")) {
            return false;
        }
        Node target = reference.operand;
        while (target instanceof ListNode list && list.elements.size() == 1) {
            target = list.elements.getFirst();
        }
        OperatorNode local = target instanceof OperatorNode op && op.operator.equals("local") ? op : null;
        Node sliceTarget = local == null ? target : local.operand;
        if (!(sliceTarget instanceof BinaryOperatorNode slice)
                || !slice.operator.equals("[")
                || !(slice.left instanceof OperatorNode arrayOp)
                || !arrayOp.operator.equals("@")
                || !(arrayOp.operand instanceof IdentifierNode arrayId)
                || !(slice.right instanceof ArrayLiteralNode indices)) {
            return false;
        }
        if (!bc.symbolTable.isFeatureCategoryEnabled("refaliasing")) {
            bc.throwCompilerException("Experimental aliasing via reference not enabled");
            return true;
        }
        emitReferenceAliasWarning(bc, node.getIndex());

        int rhsReg = compileRhs(bc, node.right, RuntimeContextType.LIST);
        int rhsListReg;
        if (node.right instanceof OperatorNode rhsArray && rhsArray.operator.equals("@")) {
            rhsListReg = rhsReg;
        } else {
            rhsListReg = bc.allocateRegister();
            bc.emit(Opcodes.SCALAR_TO_LIST);
            bc.emitReg(rhsListReg);
            bc.emitReg(rhsReg);
        }

        String arrayName = "@" + arrayId.name;
        int arrayReg;
        if (bc.hasVariable(arrayName) && !bc.isOurVariable(arrayName)) {
            arrayReg = bc.getVariableRegister(arrayName);
        } else {
            arrayReg = bc.allocateRegister();
            int nameIdx = bc.addToStringPool(NameNormalizer.normalizeVariableName(
                    arrayId.name, bc.getCurrentPackage()));
            bc.emit(Opcodes.LOAD_GLOBAL_ARRAY);
            bc.emitReg(arrayReg);
            bc.emit(nameIdx);
        }

        for (int i = 0; i < indices.elements.size(); i++) {
            bc.compileNode(indices.elements.get(i), -1, RuntimeContextType.SCALAR);
            int indexReg = bc.lastResultReg;
            if (local != null) {
                int discardedReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_DELETE_LOCAL);
                bc.emitReg(discardedReg);
                bc.emitReg(arrayReg);
                bc.emitReg(indexReg);
            }
            int targetReg = bc.allocateRegister();
            bc.emit(Opcodes.ARRAY_GET_LVALUE);
            bc.emitReg(targetReg);
            bc.emitReg(arrayReg);
            bc.emitReg(indexReg);

            int rhsIndexReg = bc.allocateRegister();
            bc.emit(Opcodes.LOAD_INT);
            bc.emitReg(rhsIndexReg);
            bc.emit(i);
            int referenceReg = bc.allocateRegister();
            bc.emit(Opcodes.ARRAY_GET);
            bc.emitReg(referenceReg);
            bc.emitReg(rhsListReg);
            bc.emitReg(rhsIndexReg);
            bc.emit(Opcodes.ALIAS_LVALUE_REFERENCE);
            bc.emitReg(targetReg);
            bc.emitReg(referenceReg);
            bc.lastResultReg = targetReg;
        }
        return true;
    }

    /**
     * Compile {@code \@hash{keys} = REFERENCES}, including its localized
     * form. As with array slices, each hash member needs its own lvalue proxy
     * and RHS reference when the JVM backend falls back to bytecode.
     */
    private static boolean compileHashSliceReferenceAliasAssignment(
            BytecodeCompiler bc, BinaryOperatorNode node) {
        if (!(node.left instanceof OperatorNode reference) || !reference.operator.equals("\\")) {
            return false;
        }
        Node target = reference.operand;
        while (target instanceof ListNode list && list.elements.size() == 1) {
            target = list.elements.getFirst();
        }
        OperatorNode local = target instanceof OperatorNode op && op.operator.equals("local") ? op : null;
        Node sliceTarget = local == null ? target : local.operand;
        if (!(sliceTarget instanceof BinaryOperatorNode slice)
                || !slice.operator.equals("{")
                || !(slice.left instanceof OperatorNode hashOp)
                || !hashOp.operator.equals("@")
                || !(hashOp.operand instanceof IdentifierNode hashId)
                || !(slice.right instanceof HashLiteralNode keys)) {
            return false;
        }
        if (!bc.symbolTable.isFeatureCategoryEnabled("refaliasing")) {
            bc.throwCompilerException("Experimental aliasing via reference not enabled");
            return true;
        }
        emitReferenceAliasWarning(bc, node.getIndex());

        int rhsReg = compileRhs(bc, node.right, RuntimeContextType.LIST);
        int rhsListReg;
        if (node.right instanceof OperatorNode rhsArray && rhsArray.operator.equals("@")) {
            rhsListReg = rhsReg;
        } else {
            rhsListReg = bc.allocateRegister();
            bc.emit(Opcodes.SCALAR_TO_LIST);
            bc.emitReg(rhsListReg);
            bc.emitReg(rhsReg);
        }

        String hashName = "%" + hashId.name;
        int hashReg;
        if (bc.hasVariable(hashName) && !bc.isOurVariable(hashName)) {
            hashReg = bc.getVariableRegister(hashName);
        } else {
            hashReg = bc.allocateRegister();
            int nameIdx = bc.addToStringPool(NameNormalizer.normalizeVariableName(
                    hashId.name, bc.getCurrentPackage()));
            bc.emit(Opcodes.LOAD_GLOBAL_HASH);
            bc.emitReg(hashReg);
            bc.emit(nameIdx);
        }

        for (int i = 0; i < keys.elements.size(); i++) {
            Node key = keys.elements.get(i);
            int keyReg;
            if (key instanceof IdentifierNode identifier) {
                keyReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_STRING);
                bc.emitReg(keyReg);
                bc.emit(bc.addToStringPool(identifier.name));
            } else {
                bc.compileNode(key, -1, RuntimeContextType.SCALAR);
                keyReg = bc.lastResultReg;
            }
            if (local != null) {
                int discardedReg = bc.allocateRegister();
                bc.emit(Opcodes.HASH_DELETE_LOCAL);
                bc.emitReg(discardedReg);
                bc.emitReg(hashReg);
                bc.emitReg(keyReg);
            }
            int targetReg = bc.allocateRegister();
            bc.emit(Opcodes.HASH_GET_FOR_LOCAL);
            bc.emitReg(targetReg);
            bc.emitReg(hashReg);
            bc.emitReg(keyReg);

            int rhsIndexReg = bc.allocateRegister();
            bc.emit(Opcodes.LOAD_INT);
            bc.emitReg(rhsIndexReg);
            bc.emit(i);
            int referenceReg = bc.allocateRegister();
            bc.emit(Opcodes.ARRAY_GET);
            bc.emitReg(referenceReg);
            bc.emitReg(rhsListReg);
            bc.emitReg(rhsIndexReg);
            bc.emit(Opcodes.ALIAS_LVALUE_REFERENCE);
            bc.emitReg(targetReg);
            bc.emitReg(referenceReg);
            bc.lastResultReg = targetReg;
        }
        return true;
    }

    /** Compile a parenthesized reference-alias assignment element by element. */
    private static boolean compileReferenceAliasListAssignment(
            BytecodeCompiler bc, BinaryOperatorNode node, int resultContext) {
        ListNode targets;
        OperatorNode listDeclaration = null;
        boolean outerReferenceToList = false;
        if (node.left instanceof ListNode list
                && list.elements.stream().allMatch(element -> element instanceof OperatorNode operator
                && operator.operator.equals("\\"))) {
            // The parser represents both (\$scalar) and
            // (\$scalar, \(@array)) as ListNodes whose members retain their
            // own reference operator.  A single member still needs the list
            // assignment lowering: its RHS must be consumed in list context.
            targets = list;
        } else {
            if (!(node.left instanceof OperatorNode referenceOp) || !referenceOp.operator.equals("\\")) {
                return false;
            }
            if (referenceOp.operand instanceof ListNode list) {
                targets = list;
                // `\\(@array)` is a reference to a parenthesized target
                // list.  It differs from `(\\@array)`, whose member
                // reference aliases the aggregate itself.
                outerReferenceToList = list.elements.size() == 1;
            } else if (referenceOp.operand instanceof OperatorNode declaration
                    && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                    && declaration.operand instanceof ListNode list) {
                targets = list;
                listDeclaration = declaration;
            } else {
                return false;
            }
        }
        if (targets.elements.isEmpty()) return false;
        if (!bc.symbolTable.isFeatureCategoryEnabled("refaliasing")) {
            bc.throwCompilerException("Experimental aliasing via reference not enabled");
            return true;
        }
        emitReferenceAliasWarning(bc, node.getIndex());

        // `\\state(@array) = ...` must retrieve the persistent aggregate
        // before evaluating its RHS.  Compiling the declaration through the
        // ordinary LVALUE path uses STATE_INIT_ARRAY, which marks the state
        // variable initialized with a temporary empty array before refaliasing
        // gets a chance to install its slots.  Besides being eager on later
        // executions, that leaves an invalid lexical register after the block.
        if (listDeclaration != null && listDeclaration.operator.equals("state")
                && targets.elements.size() == 1
                && targets.elements.getFirst() instanceof OperatorNode stateArray
                && stateArray.operator.equals("@")
                && stateArray.operand instanceof IdentifierNode stateArrayId) {
            String varName = "@" + stateArrayId.name;
            int persistId = stateArray.id;
            int nameIdx = bc.addToStringPool(varName);
            int arrayReg = bc.allocateStateVariableRegister();
            int initializedReg = bc.allocateRegister();
            bc.emitWithToken(Opcodes.STATE_RETRIEVE_ARRAY, node.getIndex());
            bc.emitReg(arrayReg);
            bc.emit(nameIdx);
            bc.emit(persistId);
            bc.emit(Opcodes.STATE_IS_INITIALIZED);
            bc.emitReg(initializedReg);
            bc.emit(nameIdx);
            bc.emit(persistId);
            bc.emit(bc.gotoIfTrueOpcode());
            bc.emitReg(initializedReg);
            int initializedJump = bc.bytecode.size();
            bc.emitInt(0);

            int rhsReg = compileRhs(bc, node.right, RuntimeContextType.LIST);
            int rhsListReg = bc.allocateRegister();
            bc.emit(Opcodes.SCALAR_TO_LIST);
            bc.emitReg(rhsListReg);
            bc.emitReg(rhsReg);
            bc.emit(Opcodes.ARRAY_SET_FROM_REFERENCE_LIST);
            bc.emitReg(arrayReg);
            bc.emitReg(rhsListReg);
            bc.emit(Opcodes.STATE_MARK_INITIALIZED);
            bc.emit(nameIdx);
            bc.emit(persistId);
            bc.patchIntOffset(initializedJump, bc.bytecode.size());
            bc.emitActiveLexicalBinding(arrayReg, varName);
            bc.registerStateArrayVariable(varName, arrayReg, persistId);
            bc.lastResultReg = arrayReg;
            return true;
        }

        // A reference to a parenthesized list produces one reference per
        // target. SET_FROM_LIST would copy values, rather than replace slots.
        int rhsReg = compileRhs(bc, node.right, RuntimeContextType.LIST);
        int rhsListReg;
        if (node.right instanceof OperatorNode arrayRhs && arrayRhs.operator.equals("@")) {
            // A direct array expression is already the flattened sequence we
            // need.  SCALAR_TO_LIST deliberately preserves aggregates by
            // wrapping them, which would make ARRAY_GET scalarize the array
            // to its element count rather than retrieve its references.
            rhsListReg = rhsReg;
        } else {
            rhsListReg = bc.allocateRegister();
            bc.emit(Opcodes.SCALAR_TO_LIST);
            bc.emitReg(rhsListReg);
            bc.emitReg(rhsReg);
        }

        for (int i = 0; i < targets.elements.size(); i++) {
            Node target = targets.elements.get(i);
            if (!isReferenceAliasRhs(node.right)
                    && target instanceof OperatorNode declaration
                    && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                    && declaration.operand instanceof OperatorNode declaredHash
                    && declaredHash.operator.equals("%")) {
                bc.throwCompilerException("Can't modify reference to parenthesized hash in list assignment");
                return true;
            }
            // A declaration wrapping the entire parenthesized target list,
            // as in \my(@array), is itself the slot-list spelling even though
            // its individual member no longer has a \ wrapper.
            boolean aggregateReferenceListTarget = listDeclaration != null || outerReferenceToList;
            if (listDeclaration != null && target instanceof OperatorNode) {
                OperatorNode declarationTarget = new OperatorNode(
                        listDeclaration.operator, target, listDeclaration.tokenIndex);
                declarationTarget.annotations = listDeclaration.annotations;
                target = declarationTarget;
            }
            if (!isReferenceAliasRhs(node.right)
                    && target instanceof OperatorNode declaration
                    && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                    && declaration.operand instanceof OperatorNode declaredHash
                    && declaredHash.operator.equals("%")) {
                bc.throwCompilerException("Can't modify reference to parenthesized hash in list assignment");
                return true;
            }
            // Each member of a parenthesized refalias list is itself a
            // reference expression: (\$scalar, \(@array)).  Lower the target
            // beneath that wrapper, while preserving declaration wrappers
            // above it.  Treating the wrapper as an ordinary lvalue copies a
            // temporary reference instead of installing the requested alias.
            if (target instanceof OperatorNode memberReference
                    && memberReference.operator.equals("\\")) {
                target = memberReference.operand;
                aggregateReferenceListTarget = target instanceof ListNode;
                while (target instanceof ListNode memberList && memberList.elements.size() == 1) {
                    target = memberList.elements.getFirst();
                }
            }
            if (target instanceof ListNode groupedTarget
                    && groupedTarget.parenthesized
                    && groupedTarget.elements.size() == 1) {
                target = groupedTarget.elements.getFirst();
                aggregateReferenceListTarget = true;
            }
            if (Boolean.TRUE.equals(target.getAnnotation("parenthesizedList"))) {
                aggregateReferenceListTarget = true;
            }
            if (!isReferenceAliasRhs(node.right)) {
                String localizedDiagnostic = invalidLocalizedArrayReferenceAlias(target, true);
                if (localizedDiagnostic != null) {
                    bc.throwCompilerException(localizedDiagnostic);
                    return true;
                }
            }
            String invalidTargetDiagnostic = invalidReferenceAliasTarget(
                    target, true, outerReferenceToList);
            if (invalidTargetDiagnostic != null) {
                bc.throwCompilerException(invalidTargetDiagnostic);
                return true;
            }
            // \my(@array) and \state(@array) retain the parenthesized
            // aggregate-list meaning even though the declaration wrapper sits
            // between the reference and its one-member ListNode.
            if (target instanceof OperatorNode declaration
                    && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                    && declaration.operand instanceof ListNode declarationList
                    && declarationList.elements.size() == 1
                    && declarationList.elements.getFirst() instanceof OperatorNode declaredAggregate
                    && declaredAggregate.operator.equals("@")) {
                OperatorNode normalizedDeclaration = new OperatorNode(
                        declaration.operator, declaredAggregate, declaration.tokenIndex);
                normalizedDeclaration.annotations = declaration.annotations;
                target = normalizedDeclaration;
                aggregateReferenceListTarget = true;
            }
            if (target instanceof OperatorNode local
                    && local.operator.equals("local")
                    && local.operand instanceof OperatorNode localScalar
                    && localScalar.operator.equals("$")
                    && localScalar.operand instanceof IdentifierNode localId) {
                int indexReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_INT);
                bc.emitReg(indexReg);
                bc.emit(i);
                int referenceReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_GET);
                bc.emitReg(referenceReg);
                bc.emitReg(rhsListReg);
                bc.emitReg(indexReg);
                int derefReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.REFALIAS_SCALAR_REFERENCE, node.getIndex());
                bc.emitReg(derefReg);
                bc.emitReg(referenceReg);

                String globalName = NameNormalizer.normalizeVariableName(
                        localId.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalName);
                int targetReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                bc.emitReg(targetReg);
                bc.emit(nameIdx);
                bc.emit(Opcodes.ALIAS_GLOBAL_SCALAR);
                bc.emit(nameIdx);
                bc.emitReg(derefReg);
                bc.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                bc.emitReg(targetReg);
                bc.emit(nameIdx);
                bc.lastResultReg = targetReg;
                continue;
            }
            if (target instanceof OperatorNode local
                    && local.operator.equals("local")
                    && local.operand instanceof OperatorNode localArray
                    && localArray.operator.equals("@")
                    && localArray.operand instanceof IdentifierNode localId) {
                // `(\local @array) = \@source` aliases the localized array
                // container itself.  It is not an array slot-list target, so
                // do not dereference the array reference as though it held
                // scalar element references.
                String globalName = NameNormalizer.normalizeVariableName(
                        localId.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalName);
                int targetReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.LOCAL_ARRAY, node.getIndex());
                bc.emitReg(targetReg);
                bc.emit(nameIdx);
                int indexReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_INT);
                bc.emitReg(indexReg);
                bc.emit(i);
                int referenceReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_GET);
                bc.emitReg(referenceReg);
                bc.emitReg(rhsListReg);
                bc.emitReg(indexReg);
                int arrayReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.FOREACH_DEREF_ARRAY, node.getIndex());
                bc.emitReg(arrayReg);
                bc.emitReg(referenceReg);
                bc.emit(Opcodes.ALIAS_GLOBAL_ARRAY);
                bc.emit(nameIdx);
                bc.emitReg(arrayReg);
                bc.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                bc.emitReg(targetReg);
                bc.emit(nameIdx);
                bc.lastResultReg = targetReg;
                continue;
            }
            if (target instanceof OperatorNode local
                    && local.operator.equals("local")
                    && local.operand instanceof OperatorNode localHash
                    && localHash.operator.equals("%")
                    && localHash.operand instanceof IdentifierNode localId) {
                String globalName = NameNormalizer.normalizeVariableName(
                        localId.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalName);
                int targetReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.LOCAL_HASH, node.getIndex());
                bc.emitReg(targetReg);
                bc.emit(nameIdx);
                int indexReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_INT);
                bc.emitReg(indexReg);
                bc.emit(i);
                int referenceReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_GET);
                bc.emitReg(referenceReg);
                bc.emitReg(rhsListReg);
                bc.emitReg(indexReg);
                int hashReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.FOREACH_DEREF_HASH, node.getIndex());
                bc.emitReg(hashReg);
                bc.emitReg(referenceReg);
                bc.emit(Opcodes.ALIAS_GLOBAL_HASH);
                bc.emit(nameIdx);
                bc.emitReg(hashReg);
                bc.emit(Opcodes.LOAD_GLOBAL_HASH);
                bc.emitReg(targetReg);
                bc.emit(nameIdx);
                bc.lastResultReg = targetReg;
                continue;
            }
            if (target instanceof OperatorNode declaration
                    && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                    && declaration.operand instanceof OperatorNode declaredScalar
                    && declaredScalar.operator.equals("$")
                    && declaredScalar.operand instanceof IdentifierNode declaredId) {
                bc.compileNode(declaration, -1, RuntimeContextType.LVALUE);
                String varName = "$" + declaredId.name;
                if (!bc.hasVariable(varName)) {
                    bc.throwCompilerException("Variable " + varName + " not found for ref aliasing");
                    return true;
                }
                int indexReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_INT);
                bc.emitReg(indexReg);
                bc.emit(i);
                int referenceReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_GET);
                bc.emitReg(referenceReg);
                bc.emitReg(rhsListReg);
                bc.emitReg(indexReg);
                int derefReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.REFALIAS_SCALAR_REFERENCE, node.getIndex());
                bc.emitReg(derefReg);
                bc.emitReg(referenceReg);
                int targetReg = bc.getVariableRegister(varName);
                // State storage is retrieved again on subsequent executions;
                // replacing only its temporary register loses a ref alias.
                // Keep that persistent cell and store the referent into it.
                bc.emit(declaration.operator.equals("state")
                        ? Opcodes.SET_SCALAR : Opcodes.ALIAS);
                bc.emitReg(targetReg);
                bc.emitReg(derefReg);
                bc.lastResultReg = targetReg;
                continue;
            }
            if (target instanceof OperatorNode declaration
                    && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                    && declaration.operand instanceof OperatorNode declaredHash
                    && declaredHash.operator.equals("%")
                    && declaredHash.operand instanceof IdentifierNode declaredId) {
                // A parenthesized declaration such as `(\my %hash)` is one
                // aggregate target.  Materialize its lexical storage before
                // replacing that container with the corresponding hash
                // referent from the RHS list.
                bc.compileNode(declaration, -1, RuntimeContextType.LVALUE);
                String varName = "%" + declaredId.name;
                if (!bc.hasVariable(varName)) {
                    bc.throwCompilerException("Variable " + varName + " not found for ref aliasing");
                    return true;
                }
                int indexReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_INT);
                bc.emitReg(indexReg);
                bc.emit(i);
                int referenceReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_GET);
                bc.emitReg(referenceReg);
                bc.emitReg(rhsListReg);
                bc.emitReg(indexReg);
                int hashReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.FOREACH_DEREF_HASH, node.getIndex());
                bc.emitReg(hashReg);
                bc.emitReg(referenceReg);
                int targetReg = bc.getVariableRegister(varName);
                bc.emit(declaration.operator.equals("state") ? Opcodes.SET_SCALAR : Opcodes.ALIAS);
                bc.emitReg(targetReg);
                bc.emitReg(hashReg);
                bc.lastResultReg = targetReg;
                continue;
            }
            if (target instanceof TernaryOperatorNode ternary
                    && compileConditionalReferenceAliasTarget(bc, ternary, rhsListReg, i, node.getIndex())) {
                continue;
            }
            if (target instanceof TernaryOperatorNode ternary) {
                int indexReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_INT);
                bc.emitReg(indexReg);
                bc.emit(i);
                int referenceReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_GET);
                bc.emitReg(referenceReg);
                bc.emitReg(rhsListReg);
                bc.emitReg(indexReg);
                compileReferenceAliasTarget(bc, ternary, referenceReg, node.getIndex());
                continue;
            }
            if (target instanceof OperatorNode scalarTarget
                    && scalarTarget.operator.equals("$")
                    && scalarTarget.operand instanceof IdentifierNode scalarId) {
                int indexReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_INT);
                bc.emitReg(indexReg);
                bc.emit(i);
                int referenceReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_GET);
                bc.emitReg(referenceReg);
                bc.emitReg(rhsListReg);
                bc.emitReg(indexReg);
                int derefReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.REFALIAS_SCALAR_REFERENCE, node.getIndex());
                bc.emitReg(derefReg);
                bc.emitReg(referenceReg);
                String varName = "$" + scalarId.name;
                if (bc.hasVariable(varName) && !bc.isOurVariable(varName)) {
                    int targetReg = bc.getVariableRegister(varName);
                    bc.emit(Opcodes.ALIAS);
                    bc.emitReg(targetReg);
                    bc.emitReg(derefReg);
                    bc.lastResultReg = targetReg;
                } else {
                    String globalName = NameNormalizer.normalizeVariableName(
                            scalarId.name, bc.getCurrentPackage());
                    int nameIdx = bc.addToStringPool(globalName);
                    bc.emit(Opcodes.ALIAS_GLOBAL_SCALAR);
                    bc.emit(nameIdx);
                    bc.emitReg(derefReg);
                    int targetReg = bc.allocateRegister();
                    bc.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                    bc.emitReg(targetReg);
                    bc.emit(nameIdx);
                    bc.lastResultReg = targetReg;
                }
                continue;
            }
            if (target instanceof OperatorNode codeTarget
                    && codeTarget.operator.equals("&")
                    && codeTarget.operand instanceof OperatorNode hiddenScalar
                    && hiddenScalar.operator.equals("$")
                    && isLexicalSubStorage(hiddenScalar)) {
                int indexReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_INT);
                bc.emitReg(indexReg);
                bc.emit(i);
                int referenceReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_GET);
                bc.emitReg(referenceReg);
                bc.emitReg(rhsListReg);
                bc.emitReg(indexReg);
                bc.compileNode(hiddenScalar, -1, RuntimeContextType.SCALAR);
                int targetReg = bc.lastResultReg;
                int codeReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.REFALIAS_CODE_REFERENCE, node.getIndex());
                bc.emitReg(codeReg);
                bc.emitReg(referenceReg);
                bc.emit(Opcodes.ALIAS);
                bc.emitReg(targetReg);
                bc.emitReg(codeReg);
                bc.lastResultReg = targetReg;
                continue;
            }
            if (target instanceof OperatorNode codeTarget
                    && codeTarget.operator.equals("&")
                    && codeTarget.operand instanceof IdentifierNode codeId) {
                // A CODE reference is already the value to install.  Unlike
                // scalar/aggregate aliases, there is no referent slot to
                // dereference: replace the package CV itself.
                int indexReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_INT);
                bc.emitReg(indexReg);
                bc.emit(i);
                int referenceReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_GET);
                bc.emitReg(referenceReg);
                bc.emitReg(rhsListReg);
                bc.emitReg(indexReg);
                int nameIdx = bc.addToStringPool(NameNormalizer.normalizeVariableName(
                        codeId.name, bc.getCurrentPackage()));
                int codeReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.REFALIAS_CODE_REFERENCE, node.getIndex());
                bc.emitReg(codeReg);
                bc.emitReg(referenceReg);
                bc.emit(Opcodes.STORE_GLOBAL_CODE);
                bc.emit(nameIdx);
                bc.emitReg(codeReg);
                int targetReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_GLOBAL_CODE);
                bc.emitReg(targetReg);
                bc.emit(nameIdx);
                bc.lastResultReg = targetReg;
                continue;
            }
            OperatorNode aggregateTarget = target instanceof OperatorNode directAggregate
                    && directAggregate.operator.equals("@") ? directAggregate : null;
            if (aggregateTarget == null && target instanceof OperatorNode declaration
                    && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                    && declaration.operand instanceof OperatorNode declaredAggregate
                    && declaredAggregate.operator.equals("@")) {
                bc.compileNode(declaration, -1, RuntimeContextType.LVALUE);
                aggregateTarget = declaredAggregate;
            }
            if (aggregateTarget != null
                    && aggregateTarget.operator.equals("@")
                    && aggregateTarget.operand instanceof IdentifierNode aggregateId) {
                if (!aggregateReferenceListTarget) {
                    compileDirectReferenceAliasTarget(bc, aggregateTarget, rhsListReg, i, node.getIndex());
                    continue;
                }
                String arrayName = "@" + aggregateId.name;
                int arrayReg;
                if (bc.hasVariable(arrayName) && !bc.isOurVariable(arrayName)) {
                    arrayReg = bc.getVariableRegister(arrayName);
                } else {
                    arrayReg = bc.allocateRegister();
                    int nameIdx = bc.addToStringPool(NameNormalizer.normalizeVariableName(
                            aggregateId.name, bc.getCurrentPackage()));
                    bc.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    bc.emitReg(arrayReg);
                    bc.emit(nameIdx);
                }
                // An aggregate member consumes every remaining RHS reference,
                // matching Perl's (\$scalar, \(@array)) list assignment.
                int remainingReferencesReg = bc.allocateRegister();
                bc.emit(Opcodes.LIST_SLICE_FROM);
                bc.emitReg(remainingReferencesReg);
                bc.emitReg(rhsListReg);
                bc.emit(i);
                bc.emit(Opcodes.ARRAY_SET_FROM_REFERENCE_LIST);
                bc.emitReg(arrayReg);
                bc.emitReg(remainingReferencesReg);
                bc.lastResultReg = arrayReg;
                continue;
            }
            if (target instanceof OperatorNode hashTarget
                    && hashTarget.operator.equals("%")
                    && hashTarget.operand instanceof IdentifierNode hashId) {
                int indexReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_INT);
                bc.emitReg(indexReg);
                bc.emit(i);
                int referenceReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_GET);
                bc.emitReg(referenceReg);
                bc.emitReg(rhsListReg);
                bc.emitReg(indexReg);
                int hashReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.FOREACH_DEREF_HASH, node.getIndex());
                bc.emitReg(hashReg);
                bc.emitReg(referenceReg);
                String hashName = "%" + hashId.name;
                if (bc.hasVariable(hashName) && !bc.isOurVariable(hashName)) {
                    int targetReg = bc.getVariableRegister(hashName);
                    bc.emit(Opcodes.ALIAS);
                    bc.emitReg(targetReg);
                    bc.emitReg(hashReg);
                    bc.lastResultReg = targetReg;
                } else {
                    int nameIdx = bc.addToStringPool(NameNormalizer.normalizeVariableName(
                            hashId.name, bc.getCurrentPackage()));
                    bc.emit(Opcodes.ALIAS_GLOBAL_HASH);
                    bc.emit(nameIdx);
                    bc.emitReg(hashReg);
                    int targetReg = bc.allocateRegister();
                    bc.emit(Opcodes.LOAD_GLOBAL_HASH);
                    bc.emitReg(targetReg);
                    bc.emit(nameIdx);
                    bc.lastResultReg = targetReg;
                }
                continue;
            }
            if (target instanceof BinaryOperatorNode element
                    && element.operator.equals("[")
                    && element.left instanceof OperatorNode arrayOp
                    && arrayOp.operator.equals("$")
                    && arrayOp.operand instanceof IdentifierNode) {
                bc.handleArrayElementLvalueAccess(element, arrayOp);
            } else if (target instanceof BinaryOperatorNode element
                    && element.operator.equals("{")) {
                bc.compileNode(element, -1, RuntimeContextType.LVALUE);
            } else if (target instanceof BinaryOperatorNode element
                    && element.operator.equals("[")) {
                bc.compileNode(element, -1, RuntimeContextType.LVALUE);
            } else {
                // Scalar targets and declaration wrappers are valid in the
                // same parenthesized alias list as array/hash elements.
                bc.compileNode(target, -1, RuntimeContextType.LVALUE);
            }
            int targetReg = bc.lastResultReg;

            int indexReg = bc.allocateRegister();
            bc.emit(Opcodes.LOAD_INT);
            bc.emitReg(indexReg);
            bc.emit(i);
            int referenceReg = bc.allocateRegister();
            bc.emit(Opcodes.ARRAY_GET);
            bc.emitReg(referenceReg);
            bc.emitReg(rhsListReg);
            bc.emitReg(indexReg);

            bc.emit(Opcodes.ALIAS_LVALUE_REFERENCE);
            bc.emitReg(targetReg);
            bc.emitReg(referenceReg);
        }
        // Like ordinary list assignment, a ref-alias list expression yields
        // the flattened RHS in list context and its element count in scalar
        // context.  rhsListReg is a RuntimeList, so LIST_TO_COUNT—not the
        // RuntimeArray-specific ARRAY_SIZE—must perform that conversion.
        if (resultContext == RuntimeContextType.SCALAR
                || resultContext == RuntimeContextType.RUNTIME) {
            int countReg = bc.allocateRegister();
            bc.emit(Opcodes.LIST_TO_COUNT);
            bc.emitReg(countReg);
            bc.emitReg(rhsListReg);
            bc.lastResultReg = countReg;
        } else if (resultContext == RuntimeContextType.LVALUE_LIST) {
            // An assignment expression used as an outer list lvalue exposes
            // temporary reference cells.  Do not return the real RHS cells:
            // a subsequent assignment would then overwrite its source
            // references.  Copy the list's scalar entries, preserving their
            // reference values while making the outer assignment disposable.
            int temporaryListReg = bc.allocateRegister();
            bc.emit(Opcodes.COPY_DO_BLOCK_RESULT);
            bc.emitReg(temporaryListReg);
            bc.emitReg(rhsListReg);
            bc.lastResultReg = temporaryListReg;
        } else {
            bc.lastResultReg = rhsListReg;
        }
        return true;
    }

    private static boolean handleLocalAssignment(BytecodeCompiler bc, BinaryOperatorNode node, OperatorNode leftOp, int rhsContext) {
        if (!leftOp.operator.equals("local")) return false;
        Node localOperand = leftOp.operand;
        if (localOperand instanceof OperatorNode sigil
                && sigil.operator.equals("$")
                && sigil.operand instanceof IdentifierNode id
                && id.name.matches("[1-9]\\d*")) {
            bc.emit(Opcodes.REJECT_READONLY_CAPTURE_ASSIGNMENT);
            return true;
        }
        // General fallback for any BinaryOperatorNode lvalue (matches JVM backend behavior)
        // Handles: local $hash{key} = v, local $array[i] = v, local $obj->method->{key} = v, etc.
        if (localOperand instanceof BinaryOperatorNode binOp) {
            // Localizing an array element replaces its slot. Saving the scalar
            // object in place is insufficient when the slot aliases a read-only
            // argument (for example local $_[3] where the caller passed '').
            // Reuse delete-local's container-aware snapshot, then vivify a fresh
            // slot for the localized value without evaluating the index twice.
            if (binOp.operator.equals("[")
                    && !(binOp.left instanceof OperatorNode op && op.operator.equals("@"))) {
                int arrayReg = CompileExistsDelete.compileArrayForExistsDelete(bc, binOp);
                int indexReg = CompileExistsDelete.compileArrayIndex(bc, binOp);
                // Perl evaluates the lvalue location, then the RHS, and only
                // then starts the localization.  In particular,
                // local $a[0] = $a[0] must copy the outer value.
                bc.compileNode(node.right, -1, rhsContext);
                int valueReg = bc.lastResultReg;
                int discardedReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_DELETE_LOCAL);
                bc.emitReg(discardedReg);
                bc.emitReg(arrayReg);
                bc.emitReg(indexReg);
                int elemReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_GET);
                bc.emitReg(elemReg);
                bc.emitReg(arrayReg);
                bc.emitReg(indexReg);
                bc.emit(Opcodes.SET_SCALAR);
                bc.emitReg(elemReg);
                bc.emitReg(valueReg);
                bc.lastResultReg = elemReg;
                return true;
            }
            boolean hashSlice = binOp.operator.equals("{")
                    && binOp.left instanceof OperatorNode sliceOp
                    && sliceOp.operator.equals("@");
            bc.beginLocalHashLvalueCompile();
            try {
                bc.compileNode(binOp, -1,
                        hashSlice ? RuntimeContextType.LIST : rhsContext);
            } finally {
                bc.endLocalHashLvalueCompile();
            }
            int elemReg = bc.lastResultReg;
            // Preserve the outer value for self-referential RHS expressions:
            // localization begins after both sides have been evaluated.
            bc.compileNode(node.right, -1, rhsContext);
            int valueReg = bc.lastResultReg;
            if (!hashSlice) {
                // Hash fetches return the live element scalar. Saving the local
                // state undefines that same object, so retain the already-
                // evaluated RHS value in an independent scalar first.
                valueReg = snapshotLocalScalarRhs(bc, valueReg);
            }
            bc.emit(Opcodes.PUSH_LOCAL_VARIABLE);
            bc.emitReg(elemReg);
            if (hashSlice) {
                int resultReg = bc.allocateOutputRegister();
                bc.emit(Opcodes.SET_FROM_LIST);
                bc.emitReg(resultReg);
                bc.emitReg(elemReg);
                bc.emitReg(valueReg);
                bc.lastResultReg = resultReg;
            } else {
                bc.emit(Opcodes.SET_SCALAR);
                bc.emitReg(elemReg);
                bc.emitReg(valueReg);
                bc.lastResultReg = elemReg;
            }
            return true;
        }
        if (localOperand instanceof OperatorNode sigilOp) {
            String sigil = sigilOp.operator;
            // local $$name: the inner scalar evaluates to a package-variable
            // name.  Localize only that scalar slot, rather than its whole
            // typeglob, so sibling array/hash/code slots remain visible.
            if (sigil.equals("$") && !(sigilOp.operand instanceof IdentifierNode)) {
                bc.compileNode(sigilOp.operand, -1, RuntimeContextType.SCALAR);
                int nameReg = bc.lastResultReg;
                int valueReg = compileLocalScalarRhs(bc, node.right);
                int localReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.LOCAL_SCALAR_DYNAMIC, node.getIndex());
                bc.emitReg(localReg);
                bc.emitReg(nameReg);
                bc.emit(Opcodes.SET_SCALAR);
                bc.emitReg(localReg);
                bc.emitReg(valueReg);
                bc.lastResultReg = localReg;
                return true;
            }
            if ((sigil.equals("$") || sigil.equals("@") || sigil.equals("%") || sigil.equals("*"))
                    && sigilOp.operand instanceof IdentifierNode idNode) {
                String varName = sigil + idNode.name;
                if (bc.hasVariable(varName) && !bc.isOurVariable(varName) && !bc.isReservedVariable(varName)) {
                    bc.throwCompilerException("Can't localize lexical variable " + varName);
                    return true;
                }
                int valueReg = (sigil.equals("$") || sigil.equals("*"))
                        ? compileLocalScalarRhs(bc, node.right)
                        : compileRhs(bc, node.right, rhsContext);
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
                        refreshOurRegister(bc, varName, nameIdx, Opcodes.LOAD_GLOBAL_SCALAR);
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
                        refreshOurRegister(bc, varName, nameIdx, Opcodes.LOAD_GLOBAL_ARRAY);
                    }
                    case "%" -> {
                        bc.emitWithToken(Opcodes.LOCAL_HASH, node.getIndex());
                        bc.emitReg(localReg);
                        bc.emit(nameIdx);
                        bc.emit(Opcodes.HASH_SET_FROM_LIST);
                        bc.emitReg(localReg);
                        bc.emitReg(valueReg);
                        refreshOurRegister(bc, varName, nameIdx, Opcodes.LOAD_GLOBAL_HASH);
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
                // Compile the glob expression. This may be either a symbolic
                // name under no strict refs or an actual glob reference.
                bc.compileNode(sigilOp.operand, -1, RuntimeContextType.SCALAR);
                int globReg = emitGlobDeref(bc, bc.lastResultReg, node.getIndex());

                // Push the glob onto the local stack
                bc.emit(Opcodes.PUSH_LOCAL_VARIABLE);
                bc.emitReg(globReg);

                // Compile the RHS value
                int valueReg = compileLocalScalarRhs(bc, node.right);

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
                // Localize the array length cell, not the whole array.  The
                // latter clears the elements and loses their values while the
                // temporary length is active.
                int sizeReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_LAST_INDEX_LVALUE);
                bc.emitReg(sizeReg);
                bc.emitReg(arrayReg);
                bc.emit(Opcodes.PUSH_LOCAL_VARIABLE);
                bc.emitReg(sizeReg);
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
        int valueReg = innerSigil.equals("$")
                ? compileLocalScalarRhs(bc, node.right)
                : compileRhs(bc, node.right, rhsContext);
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
                bc.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                bc.emitReg(ourReg);
                bc.emit(nameIdx);
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
                bc.emit(Opcodes.LOAD_GLOBAL_HASH);
                bc.emitReg(ourReg);
                bc.emit(nameIdx);
            }
            default -> {
                bc.throwCompilerException("Unsupported variable type in local our: " + innerSigil);
                return true;
            }
        }
        bc.lastResultReg = localReg;
        return true;
    }

    private static void refreshOurRegister(BytecodeCompiler bc, String varName, int nameIdx, int loadOpcode) {
        if (bc.hasVariable(varName) && bc.isOurVariable(varName)) {
            bc.emit(loadOpcode);
            bc.emitReg(bc.getVariableRegister(varName));
            bc.emit(nameIdx);
        }
    }

    private static boolean tryAddAssignOptimization(BytecodeCompiler bc, BinaryOperatorNode node, int rhsContext) {
        if (!(node.left instanceof OperatorNode leftOp) || !(node.right instanceof BinaryOperatorNode rightBin)) return false;
        if (!leftOp.operator.equals("$") || !(leftOp.operand instanceof IdentifierNode leftId)) return false;
        if (!rightBin.operator.equals("+") || !(rightBin.left instanceof OperatorNode rightLeftOp)) return false;
        if (!rightLeftOp.operator.equals("$") || !(rightLeftOp.operand instanceof IdentifierNode rightLeftId)) return false;
        String leftVarName = "$" + leftId.name;
        String rightLeftVarName = "$" + rightLeftId.name;
        boolean isCaptured = bc.capturedVarIndices != null && bc.capturedVarIndices.containsKey(leftVarName);
        String normalizedGlobalName = "$" + NameNormalizer.normalizeVariableName(
                leftId.name, bc.getCurrentPackage());
        Integer foreachGlobalAliasReg = bc.getForeachGlobalAliasRegister(normalizedGlobalName);
        if (!leftVarName.equals(rightLeftVarName)
                || (!bc.hasVariable(leftVarName) && foreachGlobalAliasReg == null)
                || isCaptured) return false;
        int targetReg = foreachGlobalAliasReg != null
                ? foreachGlobalAliasReg
                : bc.getVariableRegister(leftVarName);
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
                if (bc.hasVariable(varName) && !bc.isOurVariable(varName)
                        && !bc.isReservedVariable(varName)) {
                    bc.throwCompilerException("Can't localize lexical variable " + varName);
                    return true;
                }
                int valueReg = compileLocalScalarRhs(bc, node.right);
                String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalVarName);
                int localReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                bc.emitReg(localReg);
                bc.emit(nameIdx);
                bc.emit(Opcodes.SET_SCALAR);
                bc.emitReg(localReg);
                bc.emitReg(valueReg);
                refreshOurRegister(bc, varName, nameIdx, Opcodes.LOAD_GLOBAL_SCALAR);
                bc.lastResultReg = localReg;
                return true;
            }
            // Single-element list with an lvalue like $h->{k}, $a[i], $obj->method->{k}, etc.
            // Delegate to the scalar-local handler (matches the `local EXPR = RHS` path at
            // line 20). Without this, the element falls through the main loop below and
            // emits nothing - a silent no-op assignment. Reproduced by:
            //     local ($h->{x}) = 99;   inside an eval-STRING-compiled sub
            if (element instanceof BinaryOperatorNode binOp) {
                if (binOp.operator.equals("[")
                        && binOp.left instanceof OperatorNode arraySliceOp
                        && arraySliceOp.operator.equals("@")
                        && binOp.right instanceof ArrayLiteralNode indices) {
                    bc.compileNode(node.right, -1, RuntimeContextType.LIST);
                    int valueListReg = bc.lastResultReg;
                    bc.handleArraySliceLvalue(binOp, arraySliceOp);
                    int targetListReg = bc.lastResultReg;
                    for (int i = 0; i < indices.elements.size(); i++) {
                        int indexReg = bc.allocateRegister();
                        bc.emit(Opcodes.LOAD_INT);
                        bc.emitReg(indexReg);
                        bc.emit(i);
                        int targetReg = bc.allocateRegister();
                        bc.emit(Opcodes.ARRAY_GET);
                        bc.emitReg(targetReg);
                        bc.emitReg(targetListReg);
                        bc.emitReg(indexReg);
                        int valueReg = bc.allocateRegister();
                        bc.emit(Opcodes.ARRAY_GET);
                        bc.emitReg(valueReg);
                        bc.emitReg(valueListReg);
                        bc.emitReg(indexReg);
                        bc.emit(Opcodes.PUSH_LOCAL_VARIABLE);
                        bc.emitReg(targetReg);
                        bc.emit(Opcodes.SET_SCALAR);
                        bc.emitReg(targetReg);
                        bc.emitReg(valueReg);
                    }
                    bc.lastResultReg = targetListReg;
                    return true;
                }
                // The RHS must be evaluated before localizing an element. In
                // particular, tied FETCH must see the pre-local value (as in
                // `local($a[i]) = $a[i]`).
                bc.compileNode(node.right, -1, rhsContext);
                int valueReg = snapshotLocalScalarRhs(bc, bc.lastResultReg);
                bc.beginLocalHashLvalueCompile();
                try {
                    bc.compileNode(binOp, -1, rhsContext);
                } finally {
                    bc.endLocalHashLvalueCompile();
                }
                int elemReg = bc.lastResultReg;
                bc.emit(Opcodes.PUSH_LOCAL_VARIABLE);
                bc.emitReg(elemReg);
                bc.emit(Opcodes.SET_SCALAR);
                bc.emitReg(elemReg);
                bc.emitReg(valueReg);
                bc.lastResultReg = elemReg;
                return true;
            }
            // Single-element list with a glob: `local(*foo) = *bar` — previously fell
            // through the main loop which only handled `$` and binary-op lvalues, so
            // the assignment was a silent no-op (op/ref.t 1).
            if (element instanceof OperatorNode globOp && globOp.operator.equals("*")
                    && globOp.operand instanceof IdentifierNode globId) {
                bc.compileNode(node.right, -1, rhsContext);
                int valueReg = bc.lastResultReg;
                String globalVarName = NameNormalizer.normalizeVariableName(globId.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalVarName);
                int localReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.LOCAL_GLOB, node.getIndex());
                bc.emitReg(localReg);
                bc.emit(nameIdx);
                bc.emit(Opcodes.STORE_GLOB);
                bc.emitReg(localReg);
                bc.emitReg(valueReg);
                bc.lastResultReg = localReg;
                return true;
            }
        }
        // A multi-target localized list assignment consumes its RHS as a
        // list, even when the final target is scalar.  The loop below indexes
        // this value with ARRAY_GET, so scalar context is not valid here.
        bc.compileNode(node.right, -1, RuntimeContextType.LIST);
        int valueReg = bc.lastResultReg;
        boolean aggregateConsumedRhs = false;
        for (int i = 0; i < listNode.elements.size(); i++) {
            Node element = listNode.elements.get(i);
            // A terminal array/hash target consumes the remaining RHS values.
            // This is the common `local (undef, @array) = @array` form used by
            // core tests: the placeholder still consumes its positional value,
            // while the localized aggregate receives the remainder.
            if (element instanceof OperatorNode sigilOp
                    && (sigilOp.operator.equals("@") || sigilOp.operator.equals("%"))
                    && sigilOp.operand instanceof IdentifierNode idNode) {
                String sigil = sigilOp.operator;
                String varName = sigil + idNode.name;
                if (bc.hasVariable(varName) && !bc.isOurVariable(varName)
                        && !bc.isReservedVariable(varName)) {
                    bc.throwCompilerException("Can't localize lexical variable " + varName);
                    return true;
                }
                String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalVarName);
                int localReg = bc.allocateRegister();
                bc.emitWithToken(sigil.equals("@") ? Opcodes.LOCAL_ARRAY : Opcodes.LOCAL_HASH, node.getIndex());
                bc.emitReg(localReg);
                bc.emit(nameIdx);
                int remainderReg;
                if (i == listNode.elements.size() - 1) {
                    remainderReg = bc.allocateRegister();
                    bc.emit(Opcodes.LIST_SLICE_FROM);
                    bc.emitReg(remainderReg);
                    bc.emitReg(valueReg);
                    bc.emit(i);
                } else {
                    // Perl assigns all remaining values to a nonterminal
                    // aggregate; later scalar targets receive undef.
                    remainderReg = valueReg;
                    aggregateConsumedRhs = true;
                }
                bc.emit(sigil.equals("@") ? Opcodes.ARRAY_SET_FROM_LIST : Opcodes.HASH_SET_FROM_LIST);
                bc.emitReg(localReg);
                bc.emitReg(remainderReg);
                refreshOurRegister(bc, varName, nameIdx,
                        sigil.equals("@") ? Opcodes.LOAD_GLOBAL_ARRAY : Opcodes.LOAD_GLOBAL_HASH);
                bc.lastResultReg = localReg;
                continue;
            }
            if (element instanceof OperatorNode sigilOp && sigilOp.operator.equals("$")
                    && sigilOp.operand instanceof IdentifierNode idNode) {
                String varName = "$" + idNode.name;
                if (bc.hasVariable(varName) && !bc.isOurVariable(varName)
                        && !bc.isReservedVariable(varName)) {
                    bc.throwCompilerException("Can't localize lexical variable " + varName);
                    return true;
                }
                String globalVarName = NameNormalizer.normalizeVariableName(idNode.name, bc.getCurrentPackage());
                int nameIdx = bc.addToStringPool(globalVarName);
                int localReg = bc.allocateRegister();
                bc.emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                bc.emitReg(localReg);
                bc.emit(nameIdx);
                int elemReg = bc.allocateRegister();
                if (aggregateConsumedRhs) {
                    bc.emit(Opcodes.LOAD_UNDEF);
                    bc.emitReg(elemReg);
                } else {
                    int idxReg = bc.allocateRegister();
                    bc.emit(Opcodes.LOAD_INT);
                    bc.emitReg(idxReg);
                    bc.emit(i);
                    bc.emit(Opcodes.ARRAY_GET);
                    bc.emitReg(elemReg);
                    bc.emitReg(valueReg);
                    bc.emitReg(idxReg);
                }
                bc.emit(Opcodes.SET_SCALAR);
                bc.emitReg(localReg);
                bc.emitReg(elemReg);
                refreshOurRegister(bc, varName, nameIdx, Opcodes.LOAD_GLOBAL_SCALAR);
                if (i == 0) bc.lastResultReg = localReg;
            } else if (element instanceof BinaryOperatorNode binOp) {
                // Element is an lvalue expression (e.g. $h->{k}, $a[i], $obj->attr).
                // Compile to get the element reference, localize it, and assign RHS[i].
                int lvalueListReg = -1;
                if (binOp.operator.equals("[")
                        && binOp.left instanceof OperatorNode arraySliceOp
                        && arraySliceOp.operator.equals("@")) {
                    bc.handleArraySliceLvalue(binOp, arraySliceOp);
                    lvalueListReg = bc.lastResultReg;
                } else if (binOp.operator.equals("[")
                        && binOp.left instanceof OperatorNode arrayOp
                        && arrayOp.operator.equals("$")
                        && arrayOp.operand instanceof IdentifierNode) {
                    bc.handleArrayElementLvalueAccess(binOp, arrayOp);
                } else {
                    bc.beginLocalHashLvalueCompile();
                    try {
                        bc.compileNode(binOp, -1, RuntimeContextType.SCALAR);
                    } finally {
                        bc.endLocalHashLvalueCompile();
                    }
                }
                int elemLvalReg = bc.lastResultReg;
                if (lvalueListReg >= 0) {
                    int targetIndexReg = bc.allocateRegister();
                    bc.emit(Opcodes.LOAD_INT);
                    bc.emitReg(targetIndexReg);
                    bc.emit(i);
                    elemLvalReg = bc.allocateRegister();
                    bc.emit(Opcodes.ARRAY_GET);
                    bc.emitReg(elemLvalReg);
                    bc.emitReg(lvalueListReg);
                    bc.emitReg(targetIndexReg);
                }
                bc.emit(Opcodes.PUSH_LOCAL_VARIABLE);
                bc.emitReg(elemLvalReg);
                int idxReg = bc.allocateRegister();
                bc.emit(Opcodes.LOAD_INT);
                bc.emitReg(idxReg);
                bc.emit(i);
                int rhsElemReg = bc.allocateRegister();
                bc.emit(Opcodes.ARRAY_GET);
                bc.emitReg(rhsElemReg);
                bc.emitReg(valueReg);
                bc.emitReg(idxReg);
                bc.emit(Opcodes.SET_SCALAR);
                bc.emitReg(elemLvalReg);
                bc.emitReg(rhsElemReg);
                if (i == 0) bc.lastResultReg = elemLvalReg;
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
        if (node.left instanceof OperatorNode leftOperator
                && leftOperator.operator.equals("substr")
                && leftOperator.operand instanceof ListNode arguments
                && arguments.elements.size() > 3) {
            bytecodeCompiler.throwCompilerException(
                    "Can't modify substr in scalar assignment");
            return;
        }
        // Determine the calling context for the RHS based on LHS type
        // Use LValueVisitor to properly determine context for all LHS patterns
        // including $array[index], $hash{key}, etc.
        int rhsContext = LValueVisitor.getContext(node);
        if (node.left instanceof OperatorNode referenceOp
                && referenceOp.operator.equals("\\")
                && (referenceOp.operand instanceof ListNode
                || referenceOp.operand instanceof OperatorNode declaration
                && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                && declaration.operand instanceof ListNode)) {
            // A parenthesized ref-alias target consumes references from its
            // RHS list even when it contains only one target.
            rhsContext = RuntimeContextType.LIST;
        }
        if (node.left instanceof OperatorNode referenceOp
                && referenceOp.operator.equals("\\")
                && isLocalizedArraySliceReferenceTarget(referenceOp.operand)) {
            // `\\local @a[0,1] = (\\$tmp) x 2` aliases one RHS
            // reference per slice member.  Scalar context collapses the
            // repeat to its count, so preserve the RHS list here.
            rhsContext = RuntimeContextType.LIST;
        }
        if (rhsContext == RuntimeContextType.VOID) {
            // VOID means not a valid L-value, but we still compile it - default to LIST
            rhsContext = RuntimeContextType.LIST;
        }

        // Set the context for subroutine calls in RHS
        int outerContext = bytecodeCompiler.currentCallContext;

        // Unary plus is normally transparent around an lvalue.  A parenthesized
        // list is the important exception: `+() = expr` is Perl's idiom for a
        // list assignment with no targets, whose scalar result is the number of
        // RHS values.  Compiling it through the scalar-lvalue path turns the
        // empty RuntimeList into undef, losing that count (notably for
        // `+() = eval ...`).  Unwrap it before choosing the assignment form.
        if (node.left instanceof OperatorNode leftOp
                && leftOp.operator.equals("+")
                && leftOp.operand instanceof ListNode listOperand) {
            compileAssignmentOperator(bytecodeCompiler,
                    new BinaryOperatorNode("=", listOperand, node.right, node.tokenIndex));
            return;
        }

        // Apart from +(), unary plus is transparent around an lvalue.  In
        // particular, +sub :lvalue { ... }->() = value must retain LVALUE
        // context for the anonymous call rather than assigning to a scalar
        // copy of its result.
        if (node.left instanceof OperatorNode leftOp
                && leftOp.operator.equals("+")) {
            compileAssignmentOperator(bytecodeCompiler,
                    new BinaryOperatorNode("=", leftOp.operand, node.right, node.tokenIndex));
            return;
        }

        if (compileStateArrayReferenceAliasAssignment(bytecodeCompiler, node)
                || compileArraySliceReferenceAliasAssignment(bytecodeCompiler, node)
                || compileHashSliceReferenceAliasAssignment(bytecodeCompiler, node)
                || compileReferenceAliasListAssignment(bytecodeCompiler, node, outerContext)) {
            return;
        }

            // Special case: my $x = value
            if (node.left instanceof OperatorNode leftOp) {
                if (leftOp.operator.equals("my") || leftOp.operator.equals("state")) {
                    // Extract variable name from "my"/"state" operand
                    Node myOperand = leftOp.operand;

                    // Handle my $x (where $x is OperatorNode("$", IdentifierNode("x")))
                    if (myOperand instanceof OperatorNode sigilOp) {
                        if (sigilOp.operator.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                            String varName = "$" + ((IdentifierNode) sigilOp.operand).name;

                            Integer beginIdObj = RuntimeCode.evalBeginIds().get(sigilOp);
                            if (beginIdObj != null) {
                                int beginId = beginIdObj;
                                int nameIdx = bytecodeCompiler.addToStringPool(varName);
                                int reg = leftOp.operator.equals("state")
                                        ? bytecodeCompiler.allocateStateVariableRegister()
                                        : bytecodeCompiler.allocateRegister();

                                bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_SCALAR, node.getIndex());
                                bytecodeCompiler.emitReg(reg);
                                bytecodeCompiler.emit(nameIdx);
                                bytecodeCompiler.emit(beginId);
                                bytecodeCompiler.emitActiveLexicalBinding(reg, varName);

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

                                bytecodeCompiler.emitVarAttrsIfNeeded(leftOp, reg, "$");

                                bytecodeCompiler.lastResultReg = reg;
                                return;
                            }

                            if (leftOp.operator.equals("state")) {
                                // State initialization is lazy: Perl does not evaluate the
                                // RHS after the first successful initialization. Retrieve and
                                // test the persistent cell before compiling that RHS so a
                                // `redo` after a skipped declaration cannot repeat its side
                                // effects.
                                int persistId = sigilOp.id;
                                int nameIdx = bytecodeCompiler.addToStringPool(varName);
                                int reg = bytecodeCompiler.allocateStateVariableRegister();
                                int initializedReg = bytecodeCompiler.allocateRegister();

                                bytecodeCompiler.emitWithToken(Opcodes.STATE_RETRIEVE_SCALAR, node.getIndex());
                                bytecodeCompiler.emitReg(reg);
                                bytecodeCompiler.emit(nameIdx);
                                bytecodeCompiler.emit(persistId);
                                bytecodeCompiler.emit(Opcodes.STATE_IS_INITIALIZED);
                                bytecodeCompiler.emitReg(initializedReg);
                                bytecodeCompiler.emit(nameIdx);
                                bytecodeCompiler.emit(persistId);

                                bytecodeCompiler.emit(bytecodeCompiler.gotoIfTrueOpcode());
                                bytecodeCompiler.emitReg(initializedReg);
                                int initializedJump = bytecodeCompiler.bytecode.size();
                                bytecodeCompiler.emitInt(0);

                                bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                                int valueReg = bytecodeCompiler.lastResultReg;
                                bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                                bytecodeCompiler.emitReg(reg);
                                bytecodeCompiler.emitReg(valueReg);
                                bytecodeCompiler.emit(Opcodes.STATE_MARK_INITIALIZED);
                                bytecodeCompiler.emit(nameIdx);
                                bytecodeCompiler.emit(persistId);
                                bytecodeCompiler.patchIntOffset(initializedJump, bytecodeCompiler.bytecode.size());
                                bytecodeCompiler.emitActiveLexicalBinding(reg, varName);

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
                                bytecodeCompiler.emitLexicalAlias(reg, varName);
                                bytecodeCompiler.emit(Opcodes.REGISTER_MY_VAR);
                                bytecodeCompiler.emitReg(reg);
                                bytecodeCompiler.emitVarAttrsIfNeeded(leftOp, reg, "$");
                                bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                                bytecodeCompiler.emitReg(reg);
                                bytecodeCompiler.emitReg(valueReg);
                                emitReleaseConsumedRhsTemp(bytecodeCompiler, node.right, valueReg, reg);
                            } else {
                                bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                                bytecodeCompiler.emitReg(reg);
                                bytecodeCompiler.emitLexicalAlias(reg, varName);
                                bytecodeCompiler.emit(Opcodes.REGISTER_MY_VAR);
                                bytecodeCompiler.emitReg(reg);
                                bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                                bytecodeCompiler.emitReg(reg);
                                bytecodeCompiler.emitReg(valueReg);
                                emitReleaseConsumedRhsTemp(bytecodeCompiler, node.right, valueReg, reg);
                            }

                            bytecodeCompiler.lastResultReg = reg;
                            return;
                        } else if (sigilOp.operator.equals("@") && sigilOp.operand instanceof IdentifierNode) {
                            // Handle my @array = ...
                            String varName = "@" + ((IdentifierNode) sigilOp.operand).name;

                            Integer beginIdArr = RuntimeCode.evalBeginIds().get(sigilOp);
                            if (beginIdArr != null) {
                                int beginId = beginIdArr;
                                int nameIdx = bytecodeCompiler.addToStringPool(varName);
                                int arrayReg = bytecodeCompiler.allocateRegister();

                                bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                                bytecodeCompiler.emitReg(arrayReg);
                                bytecodeCompiler.emit(nameIdx);
                                bytecodeCompiler.emit(beginId);
                                bytecodeCompiler.emitActiveLexicalBinding(arrayReg, varName);

                                // An array assignment always evaluates its RHS in list context.
                                // The context inferred from a nested declaration can otherwise be
                                // scalar (notably for `my @caught = eval { ... }`).
                                bytecodeCompiler.compileNode(node.right, -1, RuntimeContextType.LIST);
                                int listReg = bytecodeCompiler.lastResultReg;

                                int countReg = -1;
                                if (outerContext == RuntimeContextType.SCALAR) {
                                    countReg = bytecodeCompiler.allocateRegister();
                                    bytecodeCompiler.emit(Opcodes.LIST_TO_COUNT);
                                    bytecodeCompiler.emitReg(countReg);
                                    bytecodeCompiler.emitReg(listReg);
                                }

                                // Populate array from list
                                bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                                bytecodeCompiler.emitReg(arrayReg);
                                bytecodeCompiler.emitReg(listReg);

                                bytecodeCompiler.registerVariable(varName, arrayReg);

                                bytecodeCompiler.emitVarAttrsIfNeeded(leftOp, arrayReg, "@");

                                if (outerContext == RuntimeContextType.SCALAR) {
                                    bytecodeCompiler.lastResultReg = countReg;
                                } else {
                                    bytecodeCompiler.lastResultReg = arrayReg;
                                }
                                return;
                            }

                            // A state aggregate must retain a non-recyclable register: the
                            // declaration can be skipped on later loop iterations while
                            // reads in the same lexical scope still need its persistent cell.
                            int arrayReg = leftOp.operator.equals("state")
                                    ? bytecodeCompiler.allocateStateVariableRegister()
                                    : bytecodeCompiler.allocateRegister();

                            bytecodeCompiler.compileNode(node.right, -1, RuntimeContextType.LIST);
                            int listReg = bytecodeCompiler.lastResultReg;

                            if (leftOp.operator.equals("state")) {
                                int persistId = sigilOp.id;
                                int nameIdx = bytecodeCompiler.addToStringPool(varName);
                                bytecodeCompiler.emitWithToken(Opcodes.STATE_INIT_ARRAY, node.getIndex());
                                bytecodeCompiler.emitReg(arrayReg);
                                bytecodeCompiler.emitReg(listReg);
                                bytecodeCompiler.emit(nameIdx);
                                bytecodeCompiler.emit(persistId);
                                bytecodeCompiler.emitActiveLexicalBinding(arrayReg, varName);
                                bytecodeCompiler.registerStateArrayVariable(varName, arrayReg, persistId);
                                bytecodeCompiler.emitVarAttrsIfNeeded(leftOp, arrayReg, "@");
                                bytecodeCompiler.lastResultReg = arrayReg;
                                return;
                            }

                            int countReg = -1;
                            if (outerContext == RuntimeContextType.SCALAR) {
                                countReg = bytecodeCompiler.allocateRegister();
                                bytecodeCompiler.emit(Opcodes.LIST_TO_COUNT);
                                bytecodeCompiler.emitReg(countReg);
                                bytecodeCompiler.emitReg(listReg);
                            }

                            bytecodeCompiler.registerVariable(varName, arrayReg);
                            bytecodeCompiler.emit(Opcodes.NEW_ARRAY);
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emitLexicalAlias(arrayReg, varName);

                            bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emitReg(listReg);

                            // Runtime attribute dispatch for my variables with attributes
                            bytecodeCompiler.emitVarAttrsIfNeeded(leftOp, arrayReg, "@");

                            if (outerContext == RuntimeContextType.SCALAR) {
                                bytecodeCompiler.lastResultReg = countReg;
                            } else {
                                bytecodeCompiler.lastResultReg = arrayReg;
                            }
                            return;
                        } else if (sigilOp.operator.equals("%") && sigilOp.operand instanceof IdentifierNode) {
                            // Handle my %hash = ...
                            String varName = "%" + ((IdentifierNode) sigilOp.operand).name;

                            Integer beginIdHash = RuntimeCode.evalBeginIds().get(sigilOp);
                            if (beginIdHash != null) {
                                int beginId = beginIdHash;
                                int nameIdx = bytecodeCompiler.addToStringPool(varName);
                                int hashReg = bytecodeCompiler.allocateRegister();

                                bytecodeCompiler.emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                                bytecodeCompiler.emitReg(hashReg);
                                bytecodeCompiler.emit(nameIdx);
                                bytecodeCompiler.emit(beginId);
                                bytecodeCompiler.emitActiveLexicalBinding(hashReg, varName);

                                // A hash assignment, like an array assignment, consumes a list.
                                bytecodeCompiler.compileNode(node.right, -1, RuntimeContextType.LIST);
                                int listReg = bytecodeCompiler.lastResultReg;

                                int countReg = -1;
                                if (outerContext == RuntimeContextType.SCALAR) {
                                    countReg = bytecodeCompiler.allocateRegister();
                                    bytecodeCompiler.emit(Opcodes.LIST_TO_COUNT);
                                    bytecodeCompiler.emitReg(countReg);
                                    bytecodeCompiler.emitReg(listReg);
                                }

                                // Populate hash from list
                                bytecodeCompiler.emit(Opcodes.HASH_SET_FROM_LIST);
                                bytecodeCompiler.emitReg(hashReg);
                                bytecodeCompiler.emitReg(listReg);

                                bytecodeCompiler.registerVariable(varName, hashReg);

                                bytecodeCompiler.emitVarAttrsIfNeeded(leftOp, hashReg, "%");

                                bytecodeCompiler.lastResultReg = outerContext == RuntimeContextType.SCALAR ? countReg : hashReg;
                                return;
                            }

                            // Regular lexical hash (not captured)
                            // Allocate register but don't add to scope yet,
                            // so that `my %h = %h` reads the outer %h on the RHS
                            int hashReg = bytecodeCompiler.allocateRegister();

                            // Compile RHS first, before adding variable to scope.  A hash
                            // assignment consumes it in list context.
                            bytecodeCompiler.compileNode(node.right, -1, RuntimeContextType.LIST);
                            int listReg = bytecodeCompiler.lastResultReg;

                            // Now add to symbol table and create hash
                            bytecodeCompiler.registerVariable(varName, hashReg);
                            bytecodeCompiler.emit(Opcodes.NEW_HASH);
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emitLexicalAlias(hashReg, varName);

                            int countReg = -1;
                            if (outerContext == RuntimeContextType.SCALAR) {
                                countReg = bytecodeCompiler.allocateRegister();
                                bytecodeCompiler.emit(Opcodes.LIST_TO_COUNT);
                                bytecodeCompiler.emitReg(countReg);
                                bytecodeCompiler.emitReg(listReg);
                            }

                            // Populate hash from list
                            bytecodeCompiler.emit(Opcodes.HASH_SET_FROM_LIST);
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emitReg(listReg);

                            // Runtime attribute dispatch for my variables with attributes
                            bytecodeCompiler.emitVarAttrsIfNeeded(leftOp, hashReg, "%");

                            bytecodeCompiler.lastResultReg = outerContext == RuntimeContextType.SCALAR ? countReg : hashReg;
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
                        emitReleaseConsumedRhsTemp(bytecodeCompiler, node.right, valueReg, reg);

                        bytecodeCompiler.lastResultReg = reg;
                        return;
                    }

                    // Handle my ($x, $y, @rest) = ... - list declaration with assignment
                    // Uses SET_FROM_LIST to match JVM backend's setFromList() semantics
                    if (myOperand instanceof ListNode listNode) {

                        // A parenthesized lexical declaration is a list
                        // assignment even when it contains just one array or
                        // hash variable.  Its RHS must therefore retain list
                        // context (in particular, for `eval { ... }`).
                        bytecodeCompiler.compileNode(node.right, -1, RuntimeContextType.LIST);
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
                            // `undef` placeholder in the my-list: my (undef, $x) = LIST.
                            // Emit a read-only undef so the LHS RuntimeList recognizes
                            // the slot as a placeholder that consumes one RHS value but
                            // binds nothing.
                            if (element instanceof OperatorNode undefOp
                                    && undefOp.operator.equals("undef")
                                    && undefOp.operand == null) {
                                int placeholderReg = bytecodeCompiler.allocateRegister();
                                bytecodeCompiler.emit(Opcodes.LOAD_UNDEF_READONLY);
                                bytecodeCompiler.emitReg(placeholderReg);
                                varRegs.add(placeholderReg);
                                continue;
                            }
                            if (element instanceof OperatorNode sigilOp) {
                                String sigil = sigilOp.operator;

                                if (sigilOp.operand instanceof IdentifierNode) {
                                    String varName = sigil + ((IdentifierNode) sigilOp.operand).name;
                                    int varReg;

                                    Integer beginIdList = RuntimeCode.evalBeginIds().get(sigilOp);
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
                                        bytecodeCompiler.emitActiveLexicalBinding(varReg, varName);
                                        bytecodeCompiler.registerVariable(varName, varReg);
                                        bytecodeCompiler.emit(Opcodes.REGISTER_MY_VAR);
                                        bytecodeCompiler.emitReg(varReg);
                                        // Attributes on a list declaration belong to every declared slot.
                                        // The assignment-specific path constructs these slots directly, so
                                        // it must dispatch attributes here before SET_FROM_LIST populates them.
                                        bytecodeCompiler.emitVarAttrsIfNeeded(leftOp, varReg, sigil);
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
                                        bytecodeCompiler.emitLexicalAlias(varReg, varName);
                                        bytecodeCompiler.emit(Opcodes.REGISTER_MY_VAR);
                                        bytecodeCompiler.emitReg(varReg);
                                        bytecodeCompiler.emitVarAttrsIfNeeded(leftOp, varReg, sigil);
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
            if (!compileForwardCodeGlobAlias(bytecodeCompiler, node.left, node.right)) {
                if (node.left instanceof OperatorNode globTarget
                        && globTarget.operator.equals("*")) {
                    compileLocalScalarRhs(bytecodeCompiler, node.right);
                } else {
                    bytecodeCompiler.compileNode(node.right, -1, rhsContext);
                }
            }
            int valueReg = bytecodeCompiler.lastResultReg;

            // Assign to LHS
            if (node.left instanceof OperatorNode leftOp) {
                if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode) {
                    String varName = "$" + ((IdentifierNode) leftOp.operand).name;

                    if (bytecodeCompiler.hasVariable(varName) && bytecodeCompiler.isOurVariable(varName)) {
                        SymbolTable.SymbolEntry ourEntry = bytecodeCompiler.symbolTable.getSymbolEntry(varName);
                        String ourPkg = (ourEntry != null && ourEntry.perlPackage() != null)
                                ? ourEntry.perlPackage()
                                : bytecodeCompiler.getCurrentPackage();
                        String bareVarName = varName.substring(1);
                        String normalizedName = NameNormalizer.normalizeVariableName(bareVarName, ourPkg);
                        int nameIdx = bytecodeCompiler.addToStringPool(normalizedName);

                        bytecodeCompiler.emit(Opcodes.STORE_GLOBAL_SCALAR);
                        bytecodeCompiler.emit(nameIdx);
                        bytecodeCompiler.emitReg(valueReg);

                        int lvalReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                        bytecodeCompiler.emitReg(lvalReg);
                        bytecodeCompiler.emit(nameIdx);
                        bytecodeCompiler.lastResultReg = lvalReg;
                    } else if (bytecodeCompiler.hasVariable(varName)) {
                        // Lexical variable - check if it's captured
                        int targetReg = bytecodeCompiler.getVariableRegister(varName);

                        if ((bytecodeCompiler.capturedVarIndices != null && bytecodeCompiler.capturedVarIndices.containsKey(varName))
                                || bytecodeCompiler.closureCapturedVarNames.contains(varName)
                                || bytecodeCompiler.isForeachAliasLexical(varName)) {
                            // Captured variables and active foreach lexical aliases use
                            // SET_SCALAR to preserve the existing RuntimeScalar identity.
                            // LOAD_UNDEF would replace the register with a new RuntimeScalar,
                            // breaking the shared reference that closures and foreach
                            // element aliases depend on.
                            bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(valueReg);
                            emitReleaseConsumedRhsTemp(bytecodeCompiler, node.right, valueReg, targetReg);
                        } else {
                            // Regular lexical assignment normally replaces the scalar object
                            // to avoid alias/local restoration bugs, but must preserve magical
                            // lexicals so tied STORE and read-only checks still fire.
                            bytecodeCompiler.emit(Opcodes.ASSIGN_LEXICAL_SCALAR);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(valueReg);
                            emitReleaseConsumedRhsTemp(bytecodeCompiler, node.right, valueReg, targetReg);
                        }

                        // ASSIGN_LEXICAL_SCALAR may replace the RuntimeScalar in
                        // the register after a temporary runtime-regex closure
                        // releases its capture. Refresh the live-cell registry so
                        // later executable runtime source captures the new cell,
                        // not the value that existed before an eval or callback.
                        if (bytecodeCompiler.tracksRuntimeRegexLexicals()) {
                            bytecodeCompiler.emitActiveLexicalBinding(targetReg, varName);
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
                    if (bytecodeCompiler.hasVariable(varName)) {
                        arrayReg = bytecodeCompiler.getVariableRegister(varName);
                    } else {
                        arrayReg = bytecodeCompiler.allocateRegister();
                        String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) leftOp.operand).name, bytecodeCompiler.getCurrentPackage());
                        int nameIdx = bytecodeCompiler.addToStringPool(globalArrayName);
                        bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emit(nameIdx);
                    }

                    int countReg = -1;
                    if (outerContext == RuntimeContextType.SCALAR) {
                        countReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.LIST_TO_COUNT);
                        bytecodeCompiler.emitReg(countReg);
                        bytecodeCompiler.emitReg(valueReg);
                    }

                    // Populate array from list using setFromList
                    bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                    bytecodeCompiler.emitReg(arrayReg);
                    bytecodeCompiler.emitReg(valueReg);

                    // In scalar context, return the array size; in list context, return the array
                    if (outerContext == RuntimeContextType.SCALAR) {
                        bytecodeCompiler.lastResultReg = countReg;
                    } else {
                        bytecodeCompiler.lastResultReg = arrayReg;
                    }
                } else if (leftOp.operator.equals("%") && leftOp.operand instanceof IdentifierNode) {
                    // Hash assignment: %hash = ...
                    String varName = "%" + ((IdentifierNode) leftOp.operand).name;

                    int hashReg;
                    if (bytecodeCompiler.hasVariable(varName)) {
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
                            // Keep `undef` placeholders in the reconstructed
                            // lvalue list so they consume their corresponding
                            // RHS value without binding it.
                            if (element instanceof OperatorNode undefOp
                                    && undefOp.operator.equals("undef")
                                    && undefOp.operand == null) {
                                int placeholderReg = bytecodeCompiler.allocateRegister();
                                bytecodeCompiler.emit(Opcodes.LOAD_UNDEF_READONLY);
                                bytecodeCompiler.emitReg(placeholderReg);
                                varRegs.add(placeholderReg);
                                continue;
                            }
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

                    // A typeglob assignment evaluates to its RHS, not the
                    // target glob.  This matters for chained assignments:
                    // *alias = *alias = \&source must feed the CODE ref into
                    // the outer assignment, as the JVM backend does.
                    bytecodeCompiler.lastResultReg = valueReg;
                } else if (leftOp.operator.equals("*") && leftOp.operand instanceof BlockNode) {
                    // Dynamic typeglob assignment: *{EXPR} = value. EXPR can
                    // return a real glob reference (Role::Tiny's _getglob
                    // idiom) or, under no strict refs, a symbolic name.
                    bytecodeCompiler.compileNode(leftOp.operand, -1, rhsContext);
                    int globReg = emitGlobDeref(
                            bytecodeCompiler, bytecodeCompiler.lastResultReg, node.getIndex());

                    // Store value to glob
                    bytecodeCompiler.emit(Opcodes.STORE_GLOB);
                    bytecodeCompiler.emitReg(globReg);
                    bytecodeCompiler.emitReg(valueReg);

                    // Preserve the RHS as the assignment result (see the
                    // named typeglob case above).
                    bytecodeCompiler.lastResultReg = valueReg;
                } else if (leftOp.operator.equals("*")) {
                    // Glob assignment where the glob comes from an expression, e.g. $ref->** = ...
                    // or 'name'->** = ...
                    // Compile the glob expression to obtain the RuntimeGlob, then store through it.
                    bytecodeCompiler.compileNode(leftOp, -1, rhsContext);
                    int globReg = bytecodeCompiler.lastResultReg;

                    bytecodeCompiler.emit(Opcodes.STORE_GLOB);
                    bytecodeCompiler.emitReg(globReg);
                    bytecodeCompiler.emitReg(valueReg);

                    // Preserve the RHS as the assignment result (see the
                    // named typeglob case above).
                    bytecodeCompiler.lastResultReg = valueReg;
                } else if (leftOp.operator.equals("+")) {
                    // Unary plus is transparent for lvalue assignment, matching LValueVisitor.
                    bytecodeCompiler.compileNode(leftOp.operand, -1, RuntimeContextType.LVALUE);
                    int lvalueReg = bytecodeCompiler.lastResultReg;

                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                    bytecodeCompiler.emitReg(lvalueReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = valueReg;
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
                    bytecodeCompiler.compileNode(node.left, -1, RuntimeContextType.LVALUE);
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
                } else if (leftOp.operator.equals("@")) {
                    // Array dereference assignment: @$r = ... or @{expr} = ...
                    // The operand should evaluate to an array reference

                    {
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

                        int countReg = -1;
                        if (outerContext == RuntimeContextType.SCALAR) {
                            countReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emit(Opcodes.LIST_TO_COUNT);
                            bytecodeCompiler.emitReg(countReg);
                            bytecodeCompiler.emitReg(valueReg);
                        }

                        // Assign the value to the dereferenced array
                        bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emitReg(valueReg);

                        // In scalar context, return array size; in list context, return the array
                        if (outerContext == RuntimeContextType.SCALAR) {
                            bytecodeCompiler.lastResultReg = countReg;
                        } else {
                            bytecodeCompiler.lastResultReg = arrayReg;
                        }
                    }
                } else if (leftOp.operator.equals("keys")
                        && leftOp.operand instanceof OperatorNode hashOp
                        && hashOp.operator.equals("%")) {
                    // `keys %hash = CAPACITY` preallocates buckets and returns
                    // the current key count, just like Perl's keys lvalue.
                    bytecodeCompiler.compileNode(hashOp, -1, RuntimeContextType.LIST);
                    int hashReg = bytecodeCompiler.lastResultReg;
                    bytecodeCompiler.emit(Opcodes.HASH_PREALLOCATE);
                    bytecodeCompiler.emitReg(hashReg);
                    bytecodeCompiler.emitReg(valueReg);
                    int keysReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.HASH_KEYS);
                    bytecodeCompiler.emitReg(keysReg);
                    bytecodeCompiler.emitReg(hashReg);
                    int resultReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.ARRAY_SIZE);
                    bytecodeCompiler.emitReg(resultReg);
                    bytecodeCompiler.emitReg(keysReg);
                    bytecodeCompiler.lastResultReg = resultReg;
                } else if (leftOp.operator.equals("$#")) {
                    int arrayReg = CompileAssignment.resolveArrayForDollarHash(bytecodeCompiler, leftOp);
                    bytecodeCompiler.emit(Opcodes.SET_ARRAY_LAST_INDEX);
                    bytecodeCompiler.emitReg(arrayReg);
                    bytecodeCompiler.emitReg(valueReg);
                    bytecodeCompiler.lastResultReg = valueReg;
                } else if (leftOp.operator.equals("%")) {
                    // Hash dereference assignment: %$r = ... or %{expr} = ...
                    // The operand should evaluate to a hash reference

                    {
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
                    }
                } else if (leftOp.operator.equals("\\")) {
                    // Ref aliasing: \$y = $ref
                    // Check that refaliasing feature is enabled
                    if (!bytecodeCompiler.symbolTable.isFeatureCategoryEnabled("refaliasing")) {
                        bytecodeCompiler.throwCompilerException("Experimental aliasing via reference not enabled");
                    }
                    emitReferenceAliasWarning(bytecodeCompiler, node.getIndex());
                    Node refAliasTarget = leftOp.operand;
                    if (leftOp.operand instanceof ListNode
                            || leftOp.operand instanceof OperatorNode declaration
                            && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                            && declaration.operand instanceof ListNode) {
                        // Parenthesized alias targets consume the first RHS
                        // reference.  A scalar reference such as `\\3` is
                        // still a one-element list here; only a direct array
                        // is already indexable without wrapping.
                        if (!(node.right instanceof OperatorNode rhsArray
                                && rhsArray.operator.equals("@"))) {
                            int listReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emit(Opcodes.SCALAR_TO_LIST);
                            bytecodeCompiler.emitReg(listReg);
                            bytecodeCompiler.emitReg(valueReg);
                            valueReg = listReg;
                        }
                        int indexReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.LOAD_INT);
                        bytecodeCompiler.emitReg(indexReg);
                        bytecodeCompiler.emit(0);
                        int firstReferenceReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.ARRAY_GET);
                        bytecodeCompiler.emitReg(firstReferenceReg);
                        bytecodeCompiler.emitReg(valueReg);
                        bytecodeCompiler.emitReg(indexReg);
                        valueReg = firstReferenceReg;
                    }
                    while (refAliasTarget instanceof ListNode listNode
                            && listNode.elements.size() == 1) {
                        refAliasTarget = listNode.elements.get(0);
                    }
                    String refAliasDiagnostic = invalidReferenceAliasTarget(
                            refAliasTarget, leftOp.operand instanceof ListNode,
                            leftOp.operand instanceof ListNode);
                    if (refAliasDiagnostic != null) {
                        bytecodeCompiler.throwCompilerException(refAliasDiagnostic);
                        return;
                    }
                    if (!isReferenceAliasRhs(node.right)) {
                        String localizedDiagnostic = invalidLocalizedArrayReferenceAlias(
                                refAliasTarget,
                                leftOp.operand instanceof ListNode
                                        || refAliasTarget instanceof OperatorNode referenceTarget
                                        && referenceTarget.operator.equals("\\")
                                        || refAliasTarget instanceof OperatorNode localTarget
                                        && localTarget.operator.equals("local")
                                        && localTarget.operand instanceof ListNode);
                        if (localizedDiagnostic != null) {
                            bytecodeCompiler.throwCompilerException(localizedDiagnostic);
                            return;
                        }
                    }
                    BinaryOperatorNode element = refAliasTarget instanceof BinaryOperatorNode binaryElement
                            ? binaryElement : null;
                    if (element != null && (element.operator.equals("{") || element.operator.equals("["))) {
                        if (element.operator.equals("[")
                                && element.left instanceof OperatorNode arrayOp
                                && arrayOp.operator.equals("$")
                                && arrayOp.operand instanceof IdentifierNode) {
                            bytecodeCompiler.handleArrayElementLvalueAccess(element, arrayOp);
                        } else {
                            bytecodeCompiler.compileNode(element, -1, RuntimeContextType.LVALUE);
                        }
                        int targetReg = bytecodeCompiler.lastResultReg;
                        bytecodeCompiler.emit(Opcodes.ALIAS_LVALUE_REFERENCE);
                        bytecodeCompiler.emitReg(targetReg);
                        bytecodeCompiler.emitReg(valueReg);
                        bytecodeCompiler.lastResultReg = targetReg;
                        return;
                    }
                    // Conditional and other computed scalar lvalues select a
                    // concrete slot when compiled in LVALUE context.  Alias
                    // that selected slot instead of rejecting the enclosing
                    // reference expression.
                    if (refAliasTarget instanceof BinaryOperatorNode computedLvalue) {
                        bytecodeCompiler.compileNode(computedLvalue, -1, RuntimeContextType.LVALUE);
                        int targetReg = bytecodeCompiler.lastResultReg;
                        bytecodeCompiler.emit(Opcodes.ALIAS_LVALUE_REFERENCE);
                        bytecodeCompiler.emitReg(targetReg);
                        bytecodeCompiler.emitReg(valueReg);
                        bytecodeCompiler.lastResultReg = targetReg;
                        return;
                    }
                    if (refAliasTarget instanceof TernaryOperatorNode computedLvalue) {
                        compileReferenceAliasTarget(
                                bytecodeCompiler, computedLvalue, valueReg, node.getIndex());
                        return;
                    }
                    // A lexical declaration can appear inside the parenthesized
                    // ref-alias target: \(my $lex) = \$package_scalar.
                    if (refAliasTarget instanceof OperatorNode declaration
                            && (declaration.operator.equals("my")
                            || declaration.operator.equals("state"))) {
                        Node declaredNode = declaration.operand;
                        boolean parenthesizedAggregateDeclaration = declaredNode instanceof ListNode;
                        if (declaredNode instanceof ListNode declaredList
                                && declaredList.elements.size() == 1) {
                            declaredNode = declaredList.elements.getFirst();
                        }
                        OperatorNode declaredVariable = declaredNode instanceof OperatorNode candidate
                                && (candidate.operator.equals("$")
                                || candidate.operator.equals("@")
                                || candidate.operator.equals("%")) ? candidate : null;
                        IdentifierNode declaredId = declaredVariable != null
                                && declaredVariable.operand instanceof IdentifierNode candidateId
                                ? candidateId : null;
                        if (declaredVariable != null && declaredId != null) {
                        if (parenthesizedAggregateDeclaration
                                && declaredVariable.operator.equals("%")
                                && !isReferenceAliasRhs(node.right)) {
                            bytecodeCompiler.throwCompilerException(
                                    "Can't modify reference to parenthesized hash in list assignment");
                            return;
                        }
                        String varName = declaredVariable.operator + declaredId.name;
                        // The normal assignment path compiles the declaration
                        // while visiting its LHS. Ref aliasing bypasses that
                        // path, so allocate and register the lexical cell here
                        // before replacing it with the RHS referent.
                        bytecodeCompiler.compileNode(declaration, -1, RuntimeContextType.LVALUE);
                        if (!bytecodeCompiler.hasVariable(varName)) {
                            bytecodeCompiler.throwCompilerException("Variable " + varName + " not found for ref aliasing");
                            return;
                        }
                        int targetReg = bytecodeCompiler.getVariableRegister(varName);
                        if (parenthesizedAggregateDeclaration && declaredVariable.operator.equals("@")) {
                            int referenceListReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emit(Opcodes.SCALAR_TO_LIST);
                            bytecodeCompiler.emitReg(referenceListReg);
                            bytecodeCompiler.emitReg(valueReg);
                            bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_REFERENCE_LIST);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(referenceListReg);
                            bytecodeCompiler.lastResultReg = targetReg;
                            return;
                        }
                        boolean directAggregateLiteral = (declaredVariable.operator.equals("@")
                                && node.right instanceof ArrayLiteralNode)
                                || (declaredVariable.operator.equals("%")
                                && node.right instanceof HashLiteralNode);
                        int sourceReg = valueReg;
                        if (!directAggregateLiteral) {
                            sourceReg = bytecodeCompiler.allocateRegister();
                            short derefOpcode = switch (declaredVariable.operator) {
                                case "$" -> Opcodes.REFALIAS_SCALAR_REFERENCE;
                                case "@" -> Opcodes.FOREACH_DEREF_ARRAY;
                                case "%" -> Opcodes.FOREACH_DEREF_HASH;
                                default -> throw new IllegalStateException("Unexpected declaration sigil");
                            };
                            bytecodeCompiler.emitWithToken(derefOpcode, node.getIndex());
                            bytecodeCompiler.emitReg(sourceReg);
                            bytecodeCompiler.emitReg(valueReg);
                        }
                        // A state declaration's register is a view of its
                        // persistent cell.  Replacing that register would
                        // leave the persisted state slot unchanged.
                        bytecodeCompiler.emit(declaration.operator.equals("state")
                                ? Opcodes.SET_SCALAR : Opcodes.ALIAS);
                        bytecodeCompiler.emitReg(targetReg);
                        bytecodeCompiler.emitReg(sourceReg);
                        // A `my @array` declaration initially registered its
                        // temporary empty container for scope cleanup.  The
                        // refalias replaces that register with the RHS array,
                        // so register the final binding as well; otherwise a
                        // later loop iteration can read a cleared register.
                        if (declaration.operator.equals("my")
                                && declaredVariable.operator.equals("@")) {
                            bytecodeCompiler.emit(Opcodes.REGISTER_MY_VAR);
                            bytecodeCompiler.emitReg(targetReg);
                        }
                        bytecodeCompiler.lastResultReg = targetReg;
                        return;
                        }
                    }
                    // `\local $global = $ref` first installs a dynamically
                    // localized scalar slot, then aliases that temporary slot
                    // to the referent.  The ordinary local-assignment path
                    // cannot be used because it would copy the reference
                    // value instead of replacing the slot.
                    if (refAliasTarget instanceof OperatorNode localizedSlice
                            && localizedSlice.operator.equals("local")
                            && localizedSlice.operand instanceof OperatorNode localVariable
                            && (localVariable.operator.equals("$")
                            || localVariable.operator.equals("@")
                            || localVariable.operator.equals("%"))
                            && localVariable.operand instanceof IdentifierNode localId) {
                        String globalName = NameNormalizer.normalizeVariableName(
                                localId.name, bytecodeCompiler.getCurrentPackage());
                        int nameIdx = bytecodeCompiler.addToStringPool(globalName);
                        int localReg = bytecodeCompiler.allocateRegister();
                        short localOpcode = switch (localVariable.operator) {
                            case "$" -> Opcodes.LOCAL_SCALAR;
                            case "@" -> Opcodes.LOCAL_ARRAY;
                            case "%" -> Opcodes.LOCAL_HASH;
                            default -> throw new IllegalStateException("Unexpected localized sigil");
                        };
                        bytecodeCompiler.emitWithToken(localOpcode, node.getIndex());
                        bytecodeCompiler.emitReg(localReg);
                        bytecodeCompiler.emit(nameIdx);
                        int derefReg = bytecodeCompiler.allocateRegister();
                        short derefOpcode = switch (localVariable.operator) {
                            case "$" -> Opcodes.REFALIAS_SCALAR_REFERENCE;
                            case "@" -> Opcodes.FOREACH_DEREF_ARRAY;
                            case "%" -> Opcodes.FOREACH_DEREF_HASH;
                            default -> throw new IllegalStateException("Unexpected localized sigil");
                        };
                        bytecodeCompiler.emitWithToken(derefOpcode, node.getIndex());
                        bytecodeCompiler.emitReg(derefReg);
                        bytecodeCompiler.emitReg(valueReg);
                        short aliasOpcode = switch (localVariable.operator) {
                            case "$" -> Opcodes.ALIAS_GLOBAL_SCALAR;
                            case "@" -> Opcodes.ALIAS_GLOBAL_ARRAY;
                            case "%" -> Opcodes.ALIAS_GLOBAL_HASH;
                            default -> throw new IllegalStateException("Unexpected localized sigil");
                        };
                        bytecodeCompiler.emit(aliasOpcode);
                        bytecodeCompiler.emit(nameIdx);
                        bytecodeCompiler.emitReg(derefReg);
                        short loadOpcode = switch (localVariable.operator) {
                            case "$" -> Opcodes.LOAD_GLOBAL_SCALAR;
                            case "@" -> Opcodes.LOAD_GLOBAL_ARRAY;
                            case "%" -> Opcodes.LOAD_GLOBAL_HASH;
                            default -> throw new IllegalStateException("Unexpected localized sigil");
                        };
                        bytecodeCompiler.emit(loadOpcode);
                        bytecodeCompiler.emitReg(localReg);
                        bytecodeCompiler.emit(nameIdx);
                        bytecodeCompiler.lastResultReg = localReg;
                        return;
                    }
                    if (refAliasTarget instanceof OperatorNode localizedElement
                            && localizedElement.operator.equals("local")
                            && localizedElement.operand != null) {
                        Node localTarget = localizedElement.operand;
                        if (localTarget instanceof ListNode localList
                                && localList.elements.size() == 1) {
                            localTarget = localList.elements.getFirst();
                        }
                        if (localTarget instanceof BinaryOperatorNode localElement
                                && localElement.operator.equals("{")) {
                            bytecodeCompiler.beginLocalHashLvalueCompile();
                            try {
                                bytecodeCompiler.compileNode(localElement, -1,
                                        RuntimeContextType.LVALUE);
                            } finally {
                                bytecodeCompiler.endLocalHashLvalueCompile();
                            }
                            int targetReg = bytecodeCompiler.lastResultReg;
                            bytecodeCompiler.emit(Opcodes.PUSH_LOCAL_VARIABLE);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emit(Opcodes.ALIAS_LVALUE_REFERENCE);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(valueReg);
                            bytecodeCompiler.lastResultReg = targetReg;
                            return;
                    }
                    if (refAliasTarget instanceof OperatorNode localizedListElement
                            && localizedListElement.operator.equals("local")
                            && localizedListElement.operand instanceof BinaryOperatorNode localSlice
                            && localSlice.operator.equals("[")
                            && localSlice.left instanceof OperatorNode arraySlice
                            && arraySlice.operator.equals("@")
                            && arraySlice.operand instanceof IdentifierNode arrayId
                            && localSlice.right instanceof ArrayLiteralNode indices) {
                        String arrayName = "@" + arrayId.name;
                        int arrayReg;
                        if (bytecodeCompiler.hasVariable(arrayName)) {
                            arrayReg = bytecodeCompiler.getVariableRegister(arrayName);
                        } else {
                            arrayReg = bytecodeCompiler.allocateRegister();
                            int nameIdx = bytecodeCompiler.addToStringPool(
                                    NameNormalizer.normalizeVariableName(
                                            arrayId.name, bytecodeCompiler.getCurrentPackage()));
                            bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emit(nameIdx);
                        }
                        for (int i = 0; i < indices.elements.size(); i++) {
                            bytecodeCompiler.compileNode(indices.elements.get(i), -1,
                                    RuntimeContextType.SCALAR);
                            int indexReg = bytecodeCompiler.lastResultReg;
                            int discardedReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emit(Opcodes.ARRAY_DELETE_LOCAL);
                            bytecodeCompiler.emitReg(discardedReg);
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emitReg(indexReg);
                            int targetReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emit(Opcodes.ARRAY_GET);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(arrayReg);
                            bytecodeCompiler.emitReg(indexReg);
                            int rhsIndexReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emit(Opcodes.LOAD_INT);
                            bytecodeCompiler.emitReg(rhsIndexReg);
                            bytecodeCompiler.emit(i);
                            int referenceReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emit(Opcodes.ARRAY_GET);
                            bytecodeCompiler.emitReg(referenceReg);
                            bytecodeCompiler.emitReg(valueReg);
                            bytecodeCompiler.emitReg(rhsIndexReg);
                            bytecodeCompiler.emit(Opcodes.ALIAS_LVALUE_REFERENCE);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(referenceReg);
                            bytecodeCompiler.lastResultReg = targetReg;
                        }
                        return;
                    }
                    if (refAliasTarget instanceof OperatorNode local
                            && local.operator.equals("local")
                            && local.operand instanceof BinaryOperatorNode localElement
                            && localElement.operator.equals("[")) {
                        int arrayReg = CompileExistsDelete.compileArrayForExistsDelete(
                                bytecodeCompiler, localElement);
                        int indexReg = CompileExistsDelete.compileArrayIndex(
                                bytecodeCompiler, localElement);
                        int discardedReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.ARRAY_DELETE_LOCAL);
                        bytecodeCompiler.emitReg(discardedReg);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emitReg(indexReg);
                        int targetReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.ARRAY_GET);
                        bytecodeCompiler.emitReg(targetReg);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emitReg(indexReg);
                        bytecodeCompiler.emit(Opcodes.ALIAS_LVALUE_REFERENCE);
                        bytecodeCompiler.emitReg(targetReg);
                        bytecodeCompiler.emitReg(valueReg);
                        bytecodeCompiler.lastResultReg = targetReg;
                        return;
                    }
                    if (refAliasTarget instanceof OperatorNode local
                            && local.operator.equals("local")
                            && local.operand instanceof ListNode localList
                            && localList.elements.size() == 1
                            && localList.elements.getFirst() instanceof BinaryOperatorNode localElement
                            && localElement.operator.equals("[")) {
                        int arrayReg = CompileExistsDelete.compileArrayForExistsDelete(
                                bytecodeCompiler, localElement);
                        int indexReg = CompileExistsDelete.compileArrayIndex(
                                bytecodeCompiler, localElement);
                        int discardedReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.ARRAY_DELETE_LOCAL);
                        bytecodeCompiler.emitReg(discardedReg);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emitReg(indexReg);
                        int targetReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.ARRAY_GET);
                        bytecodeCompiler.emitReg(targetReg);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emitReg(indexReg);
                        bytecodeCompiler.emit(Opcodes.ALIAS_LVALUE_REFERENCE);
                        bytecodeCompiler.emitReg(targetReg);
                        bytecodeCompiler.emitReg(valueReg);
                        bytecodeCompiler.lastResultReg = targetReg;
                        return;
                    }
                    // Handle ref aliasing: \$y = $ref, \@y = $ref, \%y = $ref
                    if (refAliasTarget instanceof OperatorNode codeTarget
                            && codeTarget.operator.equals("&")
                            && codeTarget.operand instanceof OperatorNode hiddenScalar
                            && hiddenScalar.operator.equals("$")
                            && isLexicalSubStorage(hiddenScalar)) {
                            bytecodeCompiler.compileNode(hiddenScalar, -1, RuntimeContextType.SCALAR);
                            int targetReg = bytecodeCompiler.lastResultReg;
                            int codeReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emitWithToken(Opcodes.REFALIAS_CODE_REFERENCE, node.getIndex());
                            bytecodeCompiler.emitReg(codeReg);
                            bytecodeCompiler.emitReg(valueReg);
                            bytecodeCompiler.emit(Opcodes.ALIAS);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(codeReg);
                            bytecodeCompiler.lastResultReg = targetReg;
                            return;
                        }
                    }
                    if (refAliasTarget instanceof OperatorNode varNode
                            && (varNode.operator.equals("$") || varNode.operator.equals("@")
                            || varNode.operator.equals("%") || varNode.operator.equals("&"))) {
                        String varName;
                        if (varNode.operand instanceof IdentifierNode idNode) {
                            varName = varNode.operator + idNode.name;
                        } else if (varNode.operator.equals("&")
                                && varNode.operand instanceof OperatorNode scalarTarget
                                && scalarTarget.operator.equals("$")) {
                            // `&$slot` is a scalar-backed CODE lvalue.  This
                            // includes lexical subs, whose parser-visible
                            // target is an `&` around a hidden scalar pad.
                            // Install the already-evaluated CODE reference in
                            // that scalar cell instead of rejecting the
                            // non-Identifier operand.
                            bytecodeCompiler.compileNode(scalarTarget, -1, RuntimeContextType.SCALAR);
                            int targetReg = bytecodeCompiler.lastResultReg;
                            int codeReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emitWithToken(Opcodes.REFALIAS_CODE_REFERENCE, node.getIndex());
                            bytecodeCompiler.emitReg(codeReg);
                            bytecodeCompiler.emitReg(valueReg);
                            bytecodeCompiler.emit(Opcodes.ALIAS);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(codeReg);
                            bytecodeCompiler.lastResultReg = targetReg;
                            return;
                        } else {
                            bytecodeCompiler.throwCompilerException("Assignment to unsupported ref aliasing target");
                            return;
                        }

                        if (bytecodeCompiler.hasVariable(varName) && !bytecodeCompiler.isOurVariable(varName)) {
                            int targetReg = bytecodeCompiler.getVariableRegister(varName);
                            boolean directAggregateLiteral = (varNode.operator.equals("@")
                                    && node.right instanceof ArrayLiteralNode)
                                    || (varNode.operator.equals("%")
                                    && node.right instanceof HashLiteralNode);
                            int sourceReg = valueReg;
                            if (!directAggregateLiteral) {
                                sourceReg = bytecodeCompiler.allocateRegister();
                                switch (varNode.operator) {
                                    case "$" -> bytecodeCompiler.emitWithToken(Opcodes.REFALIAS_SCALAR_REFERENCE, node.getIndex());
                                    case "@" -> bytecodeCompiler.emitWithToken(Opcodes.FOREACH_DEREF_ARRAY, node.getIndex());
                                    case "%" -> bytecodeCompiler.emitWithToken(Opcodes.FOREACH_DEREF_HASH, node.getIndex());
                                    default -> throw new IllegalStateException("Unexpected ref aliasing target: " + varNode.operator);
                                }
                                bytecodeCompiler.emitReg(sourceReg);
                                bytecodeCompiler.emitReg(valueReg);
                            }
                            // Alias: make targetReg share the same object as derefReg
                            bytecodeCompiler.emit(Opcodes.ALIAS);
                            bytecodeCompiler.emitReg(targetReg);
                            bytecodeCompiler.emitReg(sourceReg);
                            // `\$left = \$right` is itself a reference
                            // expression.  Keep the original RHS reference
                            // for callers such as foo(\$left = \$right),
                            // rather than returning the dereferenced cell.
                            bytecodeCompiler.lastResultReg = valueReg;
                        } else {
                            String globalName = NameNormalizer.normalizeVariableName(
                                    varName.substring(1), bytecodeCompiler.getCurrentPackage());
                            if (varNode.operator.equals("&")) {
                                int nameIdx = bytecodeCompiler.addToStringPool(globalName);
                                int codeReg = bytecodeCompiler.allocateRegister();
                                bytecodeCompiler.emitWithToken(Opcodes.REFALIAS_CODE_REFERENCE, node.getIndex());
                                bytecodeCompiler.emitReg(codeReg);
                                bytecodeCompiler.emitReg(valueReg);
                                bytecodeCompiler.emit(Opcodes.STORE_GLOBAL_CODE);
                                bytecodeCompiler.emit(nameIdx);
                                bytecodeCompiler.emitReg(codeReg);
                                int targetReg = bytecodeCompiler.allocateRegister();
                                bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_CODE);
                                bytecodeCompiler.emitReg(targetReg);
                                bytecodeCompiler.emit(nameIdx);
                                bytecodeCompiler.lastResultReg = targetReg;
                                return;
                            }
                            boolean directAggregateLiteral = (varNode.operator.equals("@")
                                    && node.right instanceof ArrayLiteralNode)
                                    || (varNode.operator.equals("%")
                                    && node.right instanceof HashLiteralNode);
                            int sourceReg = valueReg;
                            if (!directAggregateLiteral) {
                                sourceReg = bytecodeCompiler.allocateRegister();
                                short derefOpcode = switch (varNode.operator) {
                                    case "$" -> Opcodes.REFALIAS_SCALAR_REFERENCE;
                                    case "@" -> Opcodes.FOREACH_DEREF_ARRAY;
                                    case "%" -> Opcodes.FOREACH_DEREF_HASH;
                                    default -> throw new IllegalStateException(
                                            "Unexpected ref aliasing target: " + varNode.operator);
                                };
                                bytecodeCompiler.emitWithToken(derefOpcode, node.getIndex());
                                bytecodeCompiler.emitReg(sourceReg);
                                bytecodeCompiler.emitReg(valueReg);
                            }
                            int nameIdx = bytecodeCompiler.addToStringPool(globalName);
                            short aliasOpcode = switch (varNode.operator) {
                                case "$" -> Opcodes.ALIAS_GLOBAL_SCALAR;
                                case "@" -> Opcodes.ALIAS_GLOBAL_ARRAY;
                                case "%" -> Opcodes.ALIAS_GLOBAL_HASH;
                                default -> throw new IllegalStateException(
                                        "Unexpected ref aliasing target: " + varNode.operator);
                            };
                            bytecodeCompiler.emit(aliasOpcode);
                            bytecodeCompiler.emit(nameIdx);
                            bytecodeCompiler.emitReg(sourceReg);
                            // The assignment expression is the original RHS
                            // reference; callers can use it as a reference in
                            // a surrounding expression without observing the
                            // newly installed aggregate directly.
                            bytecodeCompiler.lastResultReg = valueReg;
                        }
                    } else {
                        bytecodeCompiler.throwCompilerException("Assignment to unsupported ref aliasing target: " + leftOp.operator);
                    }
                } else {
                    if (leftOp.operator.equals("chop") || leftOp.operator.equals("chomp")
                            || leftOp.operator.equals("substr")) {
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

                    // Must be @array or @$ref (not $array)
                    if (arrayOp.operator.equals("@")) {
                        int arrayReg;

                        if (arrayOp.operand instanceof IdentifierNode) {
                            String varName = "@" + ((IdentifierNode) arrayOp.operand).name;

                            if (bytecodeCompiler.hasVariable(varName)) {
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
                        } else if (arrayOp.operand instanceof OperatorNode || arrayOp.operand instanceof BlockNode) {
                            // @$ref[@idx] = ... or @{expr}[@idx] = ...
                            // Compile the scalar reference expression and dereference to array
                            bytecodeCompiler.compileNode(arrayOp.operand, -1, RuntimeContextType.SCALAR);
                            int scalarReg = bytecodeCompiler.lastResultReg;
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
                            bytecodeCompiler.throwCompilerException("Array slice assignment requires identifier or reference");
                            return;
                        }

                        // Compile indices (right side of [])
                        // ArrayLiteralNode contains the indices
                        if (!(leftBin.right instanceof ArrayLiteralNode)) {
                            bytecodeCompiler.throwCompilerException("Array slice assignment requires index list");
                        }

                        ArrayLiteralNode indicesNode = (ArrayLiteralNode) leftBin.right;
                        List<Integer> indexRegs = new ArrayList<>();
                        for (Node indexNode : indicesNode.elements) {
                            bytecodeCompiler.compileNode(indexNode, -1, RuntimeContextType.LIST);
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

                        int sliceValuesReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.NEW_ARRAY);
                        bytecodeCompiler.emitReg(sliceValuesReg);
                        bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                        bytecodeCompiler.emitReg(sliceValuesReg);
                        bytecodeCompiler.emitReg(valueReg);

                        // Emit direct opcode ARRAY_SLICE_SET using the materialized RHS values.
                        bytecodeCompiler.emit(Opcodes.ARRAY_SLICE_SET);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emitReg(indicesReg);
                        bytecodeCompiler.emitReg(sliceValuesReg);

                        bytecodeCompiler.lastResultReg = sliceValuesReg;
                        
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

                            if (bytecodeCompiler.hasVariable(arrayVarName)
                                    && !bytecodeCompiler.isOurVariable(arrayVarName)) {
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
                        } else if (arrayOp.operator.equals("$")
                                && (arrayOp.operand instanceof OperatorNode
                                || arrayOp.operand instanceof BlockNode)) {
                            // ${expr}[index] = value: evaluate the scalar
                            // expression first, then use it as an array
                            // reference (or symbolic name under no strict
                            // refs), just like the corresponding slice path.
                            bytecodeCompiler.compileNode(arrayOp.operand, -1, RuntimeContextType.SCALAR);
                            int scalarReg = bytecodeCompiler.lastResultReg;
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

                                if (bytecodeCompiler.hasVariable(hashVarName)
                                        && !bytecodeCompiler.isOurVariable(hashVarName)) {
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
                            } else if (hashOp.operand instanceof OperatorNode
                                    || hashOp.operand instanceof BlockNode) {
                                // Handles both:
                                //   @$ref{keys}   — hashOp.operand is OperatorNode("$", ...)
                                //   @{EXPR}{keys} — hashOp.operand is BlockNode wrapping an
                                //                   expression that evaluates to a hashref
                                // Compile the operand to a scalar ref, then deref as hash.
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
                                    // Expression key - list context lets @keys and list-returning subs expand.
                                    bytecodeCompiler.compileNode(keyElement, -1, RuntimeContextType.LIST);
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

                            int sliceValuesReg = bytecodeCompiler.allocateRegister();
                            bytecodeCompiler.emit(Opcodes.NEW_ARRAY);
                            bytecodeCompiler.emitReg(sliceValuesReg);
                            bytecodeCompiler.emit(Opcodes.ARRAY_SET_FROM_LIST);
                            bytecodeCompiler.emitReg(sliceValuesReg);
                            bytecodeCompiler.emitReg(valueReg);

                            // Emit direct opcode HASH_SLICE_SET using the materialized RHS values.
                            bytecodeCompiler.emit(Opcodes.HASH_SLICE_SET);
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emitReg(keysListReg);
                            bytecodeCompiler.emitReg(sliceValuesReg);

                            bytecodeCompiler.lastResultReg = sliceValuesReg;
                            
                            return;
                        } else if (hashOp.operator.equals("$")) {
                            // $hash{key} or $$ref{key} - dereference to get hash
                            if (hashOp.operand instanceof IdentifierNode) {
                                String varName = ((IdentifierNode) hashOp.operand).name;
                                String hashVarName = "%" + varName;

                                if (bytecodeCompiler.hasVariable(hashVarName)
                                        && !bytecodeCompiler.isOurVariable(hashVarName)) {
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
                    if (keyNode.elements.size() > 1) {
                        // Perl's $hash{a, b} is one multidimensional key,
                        // not a hash slice.  Join it with $; just as reads do.
                        keyReg = bytecodeCompiler.compileMultidimensionalHashKey(keyNode);
                    } else {
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
                            if (hashKey.elements.size() > 1) {
                                keyReg = bytecodeCompiler.compileMultidimensionalHashKey(hashKey);
                            } else {
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

                // Handle lvalue method/code-reference calls: $obj->method() = value, $code->() = value
                if (leftBin.operator.equals("->")
                        && (leftBin.right instanceof ListNode
                        || (leftBin.right instanceof BinaryOperatorNode call && call.operator.equals("(")))) {
                    bytecodeCompiler.compileNode(node.left, -1, RuntimeContextType.LVALUE);
                    int lvalueReg = bytecodeCompiler.lastResultReg;

                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                    bytecodeCompiler.emitReg(lvalueReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = valueReg;
                    return;
                }

                // Handle lvalue subroutine: f() = value
                if (leftBin.operator.equals("(")) {
                    bytecodeCompiler.compileNode(node.left, -1, RuntimeContextType.LVALUE);
                    int lvalueReg = bytecodeCompiler.lastResultReg;

                    bytecodeCompiler.emit(Opcodes.SET_SCALAR);
                    bytecodeCompiler.emitReg(lvalueReg);
                    bytecodeCompiler.emitReg(valueReg);

                    bytecodeCompiler.lastResultReg = valueReg;
                    
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

                if (leftBin.operator.equals("=~")
                        && leftBin.left instanceof OperatorNode reference
                        && reference.operator.equals("\\")
                        && leftBin.right instanceof OperatorNode transliteration
                        && transliteration.operator.equals("tr")) {
                    bytecodeCompiler.throwCompilerException(
                            "Can't modify transliteration (tr///) in scalar assignment");
                    return;
                }

                bytecodeCompiler.throwCompilerException("Assignment to non-identifier not yet supported: " + node.left.getClass().getSimpleName());
            } else if (node.left instanceof TernaryOperatorNode ternary) {
                String invalidTernaryDiagnostic = invalidReferenceAliasTernary(ternary);
                if (invalidTernaryDiagnostic != null) {
                    bytecodeCompiler.throwCompilerException(invalidTernaryDiagnostic);
                    return;
                }
                if (node.right instanceof OperatorNode reference
                        && reference.operator.equals("\\")) {
                    if (!bytecodeCompiler.symbolTable.isFeatureCategoryEnabled("refaliasing")) {
                        bytecodeCompiler.throwCompilerException("Experimental aliasing via reference not enabled");
                        return;
                    }
                    emitReferenceAliasWarning(bytecodeCompiler, node.getIndex());
                    bytecodeCompiler.compileNode(node.right, -1, RuntimeContextType.SCALAR);
                    int referenceReg = bytecodeCompiler.lastResultReg;
                    compileReferenceAliasTarget(
                            bytecodeCompiler, (TernaryOperatorNode) node.left, referenceReg, node.getIndex(), false);
                    return;
                }
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

                // Compile LHS ListNode in list-lvalue context - this produces a RuntimeList of lvalues
                // This follows the JVM backend approach (EmitVariable.java line 837)
                bytecodeCompiler.compileNode(listNode, -1, RuntimeContextType.LVALUE_LIST);
                int lhsListReg = bytecodeCompiler.lastResultReg;

                int countReg = -1;
                if (outerContext == RuntimeContextType.SCALAR) {
                    countReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.LIST_TO_COUNT);
                    bytecodeCompiler.emitReg(countReg);
                    bytecodeCompiler.emitReg(rhsListReg);
                }

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
                    bytecodeCompiler.lastResultReg = countReg;
                } else if (outerContext == RuntimeContextType.RUNTIME) {
                    // A final list assignment in a subroutine inherits the
                    // caller's context. Preserve the list-assignment result
                    // for list callers, but expose its recorded RHS count to
                    // scalar callers (for example: sub { () = /.../g }).
                    int dynamicResultReg = bytecodeCompiler.allocateOutputRegister();
                    bytecodeCompiler.emit(Opcodes.SCALAR_IF_WANTARRAY);
                    bytecodeCompiler.emitReg(dynamicResultReg);
                    bytecodeCompiler.emitReg(resultReg);
                    bytecodeCompiler.emitReg(2); // wantarray register
                    bytecodeCompiler.lastResultReg = dynamicResultReg;
                } else {
                    // In list context, return the assigned values (after hash dedup etc.)
                    bytecodeCompiler.lastResultReg = resultReg;
                }

            } else {
                bytecodeCompiler.throwCompilerException("Assignment to non-identifier not yet supported: " + node.left.getClass().getSimpleName());
            }
    }

    private static boolean isLocalizedArraySlice(Node target) {
        if (target instanceof ListNode list && list.elements.size() == 1) {
            target = list.elements.getFirst();
        }
        return target instanceof BinaryOperatorNode slice
                && slice.operator.equals("[")
                && slice.left instanceof OperatorNode aggregate
                && aggregate.operator.equals("@");
    }

    private static boolean isLocalizedArraySliceReferenceTarget(Node target) {
        while (target instanceof ListNode list && list.elements.size() == 1) {
            target = list.elements.getFirst();
        }
        return target instanceof OperatorNode local
                && local.operator.equals("local")
                && isLocalizedArraySlice(local.operand);
    }

    static int resolveArrayForDollarHash(BytecodeCompiler bytecodeCompiler, OperatorNode dollarHashOp) {
        if (dollarHashOp.operand instanceof OperatorNode operandOp
                && operandOp.operator.equals("@") && operandOp.operand instanceof IdentifierNode idNode) {
            String varName = "@" + idNode.name;
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
