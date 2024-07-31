import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * The EmitterContext class holds the context information required for emitting bytecode.
 * This includes details about the file, class, symbol table, method visitor, and context type.
 */
public class EmitterContext {
  
  /** The name of the file being processed. */
  public String fileName;
  
  /** The name of the Java class being generated. */
  public String javaClassName;
  
  /** The symbol table used for scoping symbols within the context. */
  public ScopedSymbolTable symbolTable;
  
  /** The label to which the method should return. */
  public Label returnLabel;
  
  /** The MethodVisitor instance used to visit the method instructions. */
  public MethodVisitor mv;
  
  /** The type of the current context, defined by the ContextType enum - VOID, SCALAR, etc */
  public ContextType contextType;
  
  /** Indicates whether the current context is for a boxed object (true) or a native object (false). */
  public boolean isBoxed;

  /**
   * Constructs a new EmitterContext with the specified parameters.
   *
   * @param fileName the name of the file being processed
   * @param javaClassName the name of the Java class being generated
   * @param symbolTable the symbol table used for scoping symbols within the context
   * @param returnLabel the label to which the method should return
   * @param mv the MethodVisitor instance used to visit the method instructions
   * @param contextType the type of the context, defined by the ContextType enum
   * @param isBoxed indicates whether the context is for a boxed object (true) or a native object (false)
   */
  public EmitterContext(
      String fileName,
      String javaClassName,
      ScopedSymbolTable symbolTable,
      Label returnLabel,
      MethodVisitor mv,
      ContextType contextType,
      boolean isBoxed) {
    this.fileName = fileName;
    this.javaClassName = javaClassName;
    this.symbolTable = symbolTable;
    this.returnLabel = returnLabel;
    this.mv = mv;
    this.contextType = contextType;
    this.isBoxed = isBoxed;
  }

  /**
   * Creates a new EmitterContext with the specified context type and isBoxed flag.
   * The other properties are copied from the current context.
   *
   * @param contextType the new context type
   * @param isBoxed the new isBoxed flag
   * @return a new EmitterContext with the updated context type and isBoxed flag
   */
  public EmitterContext with(ContextType contextType, boolean isBoxed) {
    return new EmitterContext(this.fileName, this.javaClassName, this.symbolTable, this.returnLabel, this.mv, contextType, isBoxed);
  }

  /**
   * Creates a new EmitterContext with the specified context type.
   * The other properties are copied from the current context.
   *
   * This is used for example when the context changes from VOID to SCALAR
   *
   * @param contextType the new context type
   * @return a new EmitterContext with the updated context type
   */
  public EmitterContext with(ContextType contextType) {
    return new EmitterContext(this.fileName, this.javaClassName, this.symbolTable, this.returnLabel, this.mv, contextType, this.isBoxed);
  }
}

