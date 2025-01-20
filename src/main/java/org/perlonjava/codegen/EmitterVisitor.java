package org.perlonjava.codegen;

import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.runtime.RuntimeContextType;

import java.util.HashMap;
import java.util.Map;

/**
 * EmitterVisitor implements the Visitor pattern to traverse the AST and generate bytecode.
 * It works in conjunction with EmitterContext to handle different runtime context types
 * during code generation.
 */
public class EmitterVisitor implements Visitor {
    /**
     * The emission context containing the current state and configuration
     */
    public final EmitterContext ctx;

    /**
     * Cache for EmitterVisitor instances with different ContextTypes.
     * Keys are context type integers, values are the corresponding EmitterVisitor instances.
     */
    private final Map<Integer, EmitterVisitor> visitorCache = new HashMap<>();

    /**
     * Creates a new EmitterVisitor with the specified context.
     *
     * @param ctx The EmitterContext to use for code generation
     */
    public EmitterVisitor(EmitterContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns an EmitterVisitor with the specified context type. Uses a cache to avoid creating new
     * instances unnecessarily.
     *
     * <p>Example usage:
     *
     * <pre>
     *   // emits the condition code in scalar context
     *   node.condition.accept(this.with(RuntimeContextType.SCALAR));
     * </pre>
     *
     * @param contextType The context type for the new EmitterVisitor.
     * @return An EmitterVisitor with the specified context type.
     */
    public EmitterVisitor with(int contextType) {
        // Check if the visitor is already cached
        if (visitorCache.containsKey(contextType)) {
            return visitorCache.get(contextType);
        }
        // Create a new visitor and cache it
        EmitterVisitor newVisitor = new EmitterVisitor(ctx.with(contextType));
        visitorCache.put(contextType, newVisitor);
        return newVisitor;
    }

    /**
     * Pushes the current call context onto the stack.
     */
    public void pushCallContext() {
        // push call context to stack
        if (ctx.contextType == RuntimeContextType.RUNTIME) {
            // Retrieve wantarray value from JVM local vars
            ctx.mv.visitVarInsn(Opcodes.ILOAD, ctx.symbolTable.getVariableIndex("wantarray"));
        } else {
            ctx.mv.visitLdcInsn(ctx.contextType);
        }
    }

    /**
     * Visit methods for different AST node types.
     * Each method delegates to specialized emitter classes that handle
     * the bytecode generation for that specific node type.
     */
    @Override
    public void visit(NumberNode node) {
        EmitLiteral.emitNumber(ctx, node);
    }

    @Override
    public void visit(IdentifierNode node) {
        EmitLiteral.emitIdentifier(ctx, node);
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        EmitBinaryOperatorNode.emitBinaryOperatorNode(this, node);
    }

    @Override
    public void visit(OperatorNode node) {
        EmitOperatorNode.emitOperatorNode(this, node);
    }

    @Override
    public void visit(TryNode node) {
        EmitStatement.emitTryCatch(this, node);
    }

    @Override
    public void visit(SubroutineNode node) {
        EmitSubroutine.emitSubroutine(ctx, node);
    }

    @Override
    public void visit(For1Node node) {
        EmitForeach.emitFor1(this, node);
    }

    @Override
    public void visit(For3Node node) {
        EmitStatement.emitFor3(this, node);
    }

    @Override
    public void visit(IfNode node) {
        EmitStatement.emitIf(this, node);
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        EmitLogicalOperator.emitTernaryOperator(this, node);
    }

    @Override
    public void visit(BlockNode node) {
        EmitBlock.emitBlock(this, node);
    }

    @Override
    public void visit(ListNode node) {
        EmitLiteral.emitList(this, node);
    }

    @Override
    public void visit(StringNode node) {
        EmitLiteral.emitString(ctx, node);
    }

    @Override
    public void visit(HashLiteralNode node) {
        EmitLiteral.emitHashLiteral(this, node);
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        EmitLiteral.emitArrayLiteral(this, node);
    }

    @Override
    public void visit(LabelNode node) {
        EmitLabel.emitLabel(ctx, node);
    }

}
