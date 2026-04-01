package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * Is this Node assignable (Lvalue) and is it Scalar-like or List-like
 * <p>
 * getResult() returns a RuntimeContextType.SCALAR, LIST, or VOID (not assignable)
 * <p>
 * Usage:
 * <p>
 * LValueVisitor.getContext(node);
 */
public class LValueVisitor implements Visitor {

    private int context = RuntimeContextType.VOID;

    public static int getContext(Node node) {
        LValueVisitor lvVisitor = new LValueVisitor();
        node.accept(lvVisitor);
        return lvVisitor.context;
    }

    @Override
    public void visit(FormatLine node) {
        // Default implementation - no action needed for format lines
    }

    @Override
    public void visit(FormatNode node) {
        // Default implementation - no action needed for format nodes
    }

    @Override
    public void visit(NumberNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(IdentifierNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        switch (node.operator) {
            case "=":   // $a = ...
                node.left.accept(this);
                break;
            case "[":   // $a[]  @a[]
            case "{":   // $a{}  @a{}
                node.left.accept(this);
                break;
            case "->":  // $a->() $a->[] $a->()
                context = RuntimeContextType.SCALAR;
                break;
            case "substr":
                context = RuntimeContextType.SCALAR;
                break;
            case "(":
                // l-value subroutine call
                // XXX TODO - check for lvalue attribute
                context = RuntimeContextType.SCALAR;
                break;
            case "&&":
            case "and":
                // Constant folding: `1 && expr` folds to `expr`, `0 && expr` folds to `0`
                handleLogicalLValue(node, true);
                break;
            case "||":
            case "or":
                // Constant folding: `0 || expr` folds to `expr`, `1 || expr` folds to `1`
                handleLogicalLValue(node, false);
                break;
            case "//":
                // Constant folding: `undef // expr` folds to `expr`, `defined // expr` folds to LHS
                handleDefinedOrLValue(node);
                break;
            default:
                context = RuntimeContextType.VOID;  // Not an L-value
        }
    }

    /**
     * Handle lvalue context for && (isAnd=true) and || (isAnd=false) with constant LHS.
     * Matches Perl's constant folding: if LHS is a compile-time constant, the logical
     * operator is eliminated and the surviving operand determines lvalue context.
     */
    private void handleLogicalLValue(BinaryOperatorNode node, boolean isAnd) {
        // Fold the LHS first (handles nested constant expressions like `1 && 2 && my $x`)
        Node foldedLeft = ConstantFoldingVisitor.foldConstants(node.left);
        RuntimeScalar constVal = ConstantFoldingVisitor.getConstantValue(foldedLeft);
        if (constVal != null) {
            boolean lhsTrue = constVal.getBoolean();
            // For &&: true LHS → RHS survives; false LHS → LHS survives (constant, not lvalue)
            // For ||: false LHS → RHS survives; true LHS → LHS survives (constant, not lvalue)
            boolean rhsSurvives = isAnd ? lhsTrue : !lhsTrue;
            if (rhsSurvives) {
                node.right.accept(this);
            } else {
                context = RuntimeContextType.VOID; // constant is not an lvalue
            }
        } else {
            context = RuntimeContextType.VOID; // non-constant LHS, not an lvalue
        }
    }

    /**
     * Handle lvalue context for // (defined-or) with constant LHS.
     */
    private void handleDefinedOrLValue(BinaryOperatorNode node) {
        Node foldedLeft = ConstantFoldingVisitor.foldConstants(node.left);
        RuntimeScalar constVal = ConstantFoldingVisitor.getConstantValue(foldedLeft);
        if (constVal != null) {
            if (constVal.getDefinedBoolean()) {
                // LHS is defined → LHS survives (constant, not lvalue)
                context = RuntimeContextType.VOID;
            } else {
                // LHS is undef → RHS survives
                node.right.accept(this);
            }
        } else {
            context = RuntimeContextType.VOID; // non-constant LHS, not an lvalue
        }
    }

    @Override
    public void visit(OperatorNode node) {
        switch (node.operator) {
            case "+":   // +$v = 2
            case "local":
            case "our":
            case "state":
            case "my":  // 'my' depends on the operand's context, can be SCALAR or LIST
                node.operand.accept(this);
                break;
            case "@":   // @a
            case "%":   // %a
                context = RuntimeContextType.LIST;
                break;
            case "&":
//                if (node.attributes.contains("lvalue")) {
//                    // lvalue subroutine
//                    context = RuntimeContextType.SCALAR;
//                } else {
//                }
                break;
            case "$":   // $a $$a
            case "*":   // typeglob
            case "\\":  // requires "refaliasing"
            case "vec":
            case "keys":
            case "pos":
            case "substr":
            case "$#":
                context = RuntimeContextType.SCALAR;
                break;
            default:
                context = RuntimeContextType.VOID;  // Not an L-value
        }
    }

    @Override
    public void visit(For1Node node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(For3Node node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(IfNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(SubroutineNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        int context1 = assignmentTypeOf(node.trueExpr);
        int context2 = assignmentTypeOf(node.falseExpr);
        if (context1 != context2) {
            throw new PerlCompilerException("Assignment to both a list and a scalar");
        }
        context = context1;
    }

    /**
     * Determine the assignment type of a ternary branch, matching Perl 5's S_assignment_type().
     * In Perl 5, assignment ops (both OP_SASSIGN and OP_AASSIGN) are not in the list of
     * op types that return ASSIGN_LIST, so they fall through to ASSIGN_SCALAR.
     * This allows patterns like: (wantarray ? @rv = eval $src : $rv[0]) = eval $src
     * where the true branch is a list assignment but is treated as SCALAR for the
     * mixed-context check.
     */
    private int assignmentTypeOf(Node expr) {
        if (expr instanceof BinaryOperatorNode binop && binop.operator.equals("=")) {
            return RuntimeContextType.SCALAR;
        }
        expr.accept(this);
        return context;
    }

    @Override
    public void visit(StringNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(BlockNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(ListNode node) {
        // Special case for ($a ? $b : $c)
        if (node.elements.size() == 1) {
            if (node.elements.get(0) instanceof TernaryOperatorNode) {
                // ($a ? $b : $c)
                node.elements.get(0).accept(this);
                return;
            }
        }

        // A list is a LIST L-value most of the time
        context = RuntimeContextType.LIST; // ($a, $b)
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(HashLiteralNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(TryNode node) {
        // A TryNode is not an L-value, so set context to VOID
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(DeferNode node) {
        // A DeferNode is not an L-value, so set context to VOID
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(LabelNode node) {
    }

    @Override
    public void visit(CompilerFlagNode node) {
    }
}

