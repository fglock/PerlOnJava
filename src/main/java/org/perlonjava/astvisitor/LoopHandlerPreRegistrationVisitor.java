package org.perlonjava.astvisitor;

import org.objectweb.asm.Label;
import org.perlonjava.astnode.*;
import org.perlonjava.codegen.LoopLabelRegistry;

/**
 * Pre-registration visitor that creates Label objects and registers loop handlers
 * BEFORE any bytecode is emitted.
 * 
 * <h2>The Two-Pass Solution for Handler Chaining</h2>
 * 
 * <p><b>The Problem:</b> When emitting nested loops, the OUTER loop needs to know
 * about INNER loop handler labels to generate delegation code (GOTO statements).
 * But INNER's labels don't exist until INNER is emitted, which happens AFTER OUTER's
 * handler code is emitted.
 * 
 * <p><b>The Solution:</b> Two-pass approach:
 * <ol>
 *   <li><b>Pass 1 (this visitor):</b> Traverse AST, create ALL Label objects upfront,
 *       register them in LoopLabelRegistry</li>
 *   <li><b>Pass 2 (normal emission):</b> Emit bytecode, using pre-registered labels.
 *       When OUTER emits handlers, INNER's labels already exist in the registry!</li>
 * </ol>
 * 
 * <p><b>Usage:</b>
 * <pre>{@code
 * // Pass 1: Pre-register all loop labels
 * LoopHandlerPreRegistrationVisitor preReg = new LoopHandlerPreRegistrationVisitor();
 * loopBodyNode.accept(preReg);
 * 
 * // Pass 2: Emit code normally - registry is fully populated
 * emitLoopCode();  // Can now look up inner labels!
 * 
 * // Cleanup
 * preReg.unregisterAll();
 * }</pre>
 */
public class LoopHandlerPreRegistrationVisitor implements Visitor {
    // Track what we've registered so we can unregister later
    private final java.util.List<String> registeredLabels = new java.util.ArrayList<>();
    
    /**
     * Unregister all labels that were registered during traversal.
     * Call this after code emission is complete.
     */
    public void unregisterAll() {
        for (String label : registeredLabels) {
            LoopLabelRegistry.unregister(label);
        }
        registeredLabels.clear();
    }
    
    @Override
    public void visit(For1Node node) {
        // Create labels and register this loop
        if (node.labelName != null) {
            Label catchNext = new Label();
            Label catchLast = new Label();
            Label catchRedo = new Label();
            
            LoopLabelRegistry.register(node.labelName, catchNext, catchLast, catchRedo);
            registeredLabels.add(node.labelName);
            
            // Store labels in the node so emission can use them
            node.setPreRegisteredLabels(catchNext, catchLast, catchRedo);
        }
        
        // Traverse body to find nested loops
        if (node.body != null) {
            node.body.accept(this);
        }
    }
    
    @Override
    public void visit(For3Node node) {
        // Create labels and register this loop
        if (node.labelName != null) {
            Label catchNext = new Label();
            Label catchLast = new Label();
            Label catchRedo = new Label();
            
            LoopLabelRegistry.register(node.labelName, catchNext, catchLast, catchRedo);
            registeredLabels.add(node.labelName);
            
            // Store labels in the node so emission can use them
            node.setPreRegisteredLabels(catchNext, catchLast, catchRedo);
        }
        
        // Traverse body to find nested loops
        if (node.body != null) {
            node.body.accept(this);
        }
    }
    
    @Override
    public void visit(BlockNode node) {
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
    }
    
    @Override
    public void visit(ListNode node) {
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
    }
    
    @Override
    public void visit(BinaryOperatorNode node) {
        if (node.left != null) node.left.accept(this);
        if (node.right != null) node.right.accept(this);
    }
    
    @Override
    public void visit(TernaryOperatorNode node) {
        if (node.condition != null) node.condition.accept(this);
        if (node.trueExpr != null) node.trueExpr.accept(this);
        if (node.falseExpr != null) node.falseExpr.accept(this);
    }
    
    @Override
    public void visit(OperatorNode node) {
        if (node.operand != null) node.operand.accept(this);
    }
    
    @Override
    public void visit(IfNode node) {
        if (node.condition != null) node.condition.accept(this);
        if (node.thenBranch != null) node.thenBranch.accept(this);
        if (node.elseBranch != null) node.elseBranch.accept(this);
    }
    
    @Override
    public void visit(TryNode node) {
        if (node.tryBlock != null) node.tryBlock.accept(this);
        if (node.catchBlock != null) node.catchBlock.accept(this);
        if (node.finallyBlock != null) node.finallyBlock.accept(this);
    }
    
    @Override
    public void visit(SubroutineNode node) {
        // Don't traverse - different scope
    }
    
    // Leaf nodes
    @Override
    public void visit(IdentifierNode node) {}
    
    @Override
    public void visit(NumberNode node) {}
    
    @Override
    public void visit(StringNode node) {}
    
    @Override
    public void visit(LabelNode node) {}
    
    @Override
    public void visit(CompilerFlagNode node) {}
    
    @Override
    public void visit(HashLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
    }
    
    @Override
    public void visit(ArrayLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
    }
    
    @Override
    public void visit(FormatNode node) {}
}

