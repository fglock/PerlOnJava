import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class ReturnNode extends CodeGeneratingNode {
    private final CodeGeneratingNode value;

    public ReturnNode(CodeGeneratingNode value) {
        this.value = value;
    }

    @Override
    public int evaluate() {
        return value.evaluate();
    }

    @Override
    public void generateCode(MethodVisitor mv) {
        value.generateCode(mv);
        mv.visitInsn(Opcodes.IRETURN);
    }
}

