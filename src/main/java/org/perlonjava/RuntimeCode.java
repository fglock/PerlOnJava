import java.lang.reflect.Method;
import java.util.*;

public class RuntimeCode {

  public Method methodObject;
  public Object codeObject; // apply() needs this

  // Temporary storage for anonymous subroutines and eval string compiler context
  public static HashMap<String, Class<?>> anonSubs =
      new HashMap<String, Class<?>>(); // temp storage for make_sub()
  public static HashMap<String, EmitterContext> evalContext =
      new HashMap<String, EmitterContext>(); // storage for eval string compiler context

  public RuntimeCode(Method methodObject, Object codeObject) {
    this.methodObject = methodObject;
    this.codeObject = codeObject;
  }

  // Method to compile the text of eval string into an anonymous subroutine
  public static Class<?> eval_string(Runtime code, String evalTag) throws Exception {

    // retrieve the eval context that was saved at program compile-time
    EmitterContext evalCtx = RuntimeCode.evalContext.get(evalTag);

    // TODO - this can be cached for performance
    // retrieve closure variable list
    // alternately, scan the AST for variables and capture only the ones that are used
    Map<Integer, String> visibleVariables = evalCtx.symbolTable.getAllVisibleVariables();
    String[] newEnv = new String[visibleVariables.size()];
    for (Integer index : visibleVariables.keySet()) {
      String variableName = visibleVariables.get(index);
      newEnv[index] = variableName;
    }

    Node ast;

    // Process the string source code to create the Token list
    Lexer lexer = new Lexer(code.toString());
    List<Token> tokens = lexer.tokenize(); // Tokenize the Perl code
    try {
      // Create the AST
      // Create an instance of ErrorMessageUtil with the file name and token list
      ErrorMessageUtil errorUtil = new ErrorMessageUtil(evalCtx.fileName, tokens);
      Parser parser = new Parser(errorUtil, tokens); // Parse the tokens
      ast = parser.parse(); // Generate the abstract syntax tree (AST)
    } catch (Exception e) {
      // compilation error in eval-string

      // Set the global error variable "$@" using Runtime.setGlobalVariable(key, value)
      Namespace.setGlobalVariable("$@", e.toString());

      ast = new UnaryOperatorNode("undef", null, 1); // return an "undef" ast
    }

    evalCtx.errorUtil = new ErrorMessageUtil(evalCtx.fileName, tokens);
    Class<?> generatedClass =
        ASMMethodCreator.createClassWithMethod(
            evalCtx, newEnv, // Closure variables
            ast,
            true  // use try-catch
    );
    return generatedClass;
  }


}
