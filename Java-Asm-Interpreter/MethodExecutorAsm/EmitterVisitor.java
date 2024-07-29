import org.objectweb.asm.Opcodes;

public class EmitterVisitor implements Visitor {
  private final EmitterContext ctx;

  public EmitterVisitor(EmitterContext ctx) {
    this.ctx = ctx;
  }

  @Override
  public void visit(NumberNode node) {
    boolean isInteger = !node.value.contains(".");
    if (ctx.isBoxed) { // expect a Runtime object
      if (isInteger) {
        System.out.println("visit(NumberNode) emit boxed integer");
        ctx.mv.visitTypeInsn(Opcodes.NEW, "Runtime");
        ctx.mv.visitInsn(Opcodes.DUP);
        ctx.mv.visitLdcInsn(
            Integer.valueOf(node.value)); // Push the integer argument onto the stack
        ctx.mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, "Runtime", "<init>", "(I)V", false); // Call new Runtime(int)
      } else {
        ctx.mv.visitTypeInsn(Opcodes.NEW, "Runtime");
        ctx.mv.visitInsn(Opcodes.DUP);
        ctx.mv.visitLdcInsn(Double.valueOf(node.value)); // Push the double argument onto the stack
        ctx.mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, "Runtime", "<init>", "(D)V", false); // Call new Runtime(double)
      }
    } else {
      if (isInteger) {
        ctx.mv.visitLdcInsn(Integer.parseInt(node.value)); // emit native integer
      } else {
        ctx.mv.visitLdcInsn(Double.parseDouble(node.value)); // emit native double
      }
    }
    System.out.println("Emit context " + (ctx.contextType == ContextType.VOID ? "void" : "scalar"));
    if (ctx.contextType == ContextType.VOID) {
      ctx.mv.visitInsn(Opcodes.POP);
    }
  }

  @Override
  public void visit(IdentifierNode node) {
    // Emit code for identifier
  }

  @Override
  public void visit(BinaryOperatorNode node) {
    node.left.accept(this);
    node.right.accept(this);
    switch (node.operator) {
      case "+":
        ctx.mv.visitInsn(Opcodes.IADD);
        break;
      case "-":
        ctx.mv.visitInsn(Opcodes.ISUB);
        break;
      case "*":
        ctx.mv.visitInsn(Opcodes.IMUL);
        break;
      case "/":
        ctx.mv.visitInsn(Opcodes.IDIV);
        break;
        // Add other operators as needed
    }
  }

  @Override
  public void visit(UnaryOperatorNode node) {
    // Emit code for unary operator
    System.out.println("visit(UnaryOperatorNode) " + node.operator);
    if (node.operator.equals("$") || node.operator.equals("@")) {
        if (node.operand instanceof IdentifierNode) { // $a
            String var = node.operator + ((IdentifierNode) node.operand).name;
            System.out.println("GETVAR " + var);
            int varIndex = ctx.symbolTable.getVariableIndex(var);
            if (varIndex == -1) {
              System.out.println(
                  "Warning: Global symbol \""
                      + var
                      + "\" requires explicit package name (did you forget to declare \"my "
                      + var
                      + "\"?)");
            }
            if (ctx.contextType != ContextType.VOID) {
              ctx.mv.visitVarInsn(Opcodes.ALOAD, varIndex);
            }
            System.out.println("GETVAR end " + varIndex);
        }
    } else if (node.operator.equals("my")) {
      // TODO
      node.operand.accept(this);
    }
    else if (node.operator.equals("return")) {
      // set call context to SCALAR - XXX might need RUNTIME call context
      EmitterContext childCtx = ctx.with(ContextType.SCALAR, ctx.isBoxed);
      node.operand.accept(new EmitterVisitor(childCtx));
      ctx.mv.visitJumpInsn(Opcodes.GOTO, ctx.returnLabel);
      // if (ctx.contextType == ContextType.VOID) {
      //     ctx.mv.visitInsn(Opcodes.POP);
      // }
    }
  }

  @Override
  public void visit(TernaryOperatorNode node) {
    node.condition.accept(this);
    // Emit code for ternary operator
  }

  @Override
  public void visit(PostfixOperatorNode node) {
    node.operand.accept(this);
    // Emit code for postfix operator
  }

  @Override
  public void visit(ListNode node) {
    for (Node element : node.elements) {
      element.accept(this);
    }
    // Emit code for list
  }

  @Override
  public void visit(StringNode node) {
    ctx.mv.visitLdcInsn(node.value); // emit string
  }

  // Add other visit methods as needed
}
