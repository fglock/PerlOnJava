package org.perlonjava;

import org.perlonjava.node.Node;
import org.perlonjava.node.UnaryOperatorNode;

import java.lang.reflect.Method;
import java.util.*;

public class RuntimeCode implements RuntimeScalarReference {

  public Method methodObject;
  public Object codeObject; // apply() needs this

  // Temporary storage for anonymous subroutines and eval string compiler context
  public static HashMap<String, Class<?>> anonSubs =
          new HashMap<>(); // temp storage for make_sub()
  public static HashMap<String, EmitterContext> evalContext =
          new HashMap<>(); // storage for eval string compiler context

  public RuntimeCode(Method methodObject, Object codeObject) {
    this.methodObject = methodObject;
    this.codeObject = codeObject;
  }

  // Method to compile the text of eval string into an anonymous subroutine
  public static Class<?> eval_string(RuntimeScalar code, String evalTag) throws Exception {

    // retrieve the eval context that was saved at program compile-time
    EmitterContext ctx = RuntimeCode.evalContext.get(evalTag);

    EmitterContext evalCtx =
            new EmitterContext(
                    "(eval)", // filename
                    EmitterMethodCreator.generateClassName(), // internal java class name
                    ctx.symbolTable.clone(), // clone the symbolTable
                    null, // return label
                    null, // method visitor
                    ctx.contextType, // call context
                    true, // is boxed
                    ctx.errorUtil, // error message utility
                    ctx.debugEnabled,
                    ctx.tokenizeOnly,
                    ctx.compileOnly,
                    ctx.parseOnly);

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

    // Process the string source code to create the LexerToken list
    Lexer lexer = new Lexer(code.toString());
    List<LexerToken> tokens = lexer.tokenize(); // Tokenize the Perl code
    try {
      // Create the AST
      // Create an instance of ErrorMessageUtil with the file name and token list
      ErrorMessageUtil errorUtil = new ErrorMessageUtil(evalCtx.fileName, tokens);
      Parser parser = new Parser(errorUtil, tokens); // Parse the tokens
      ast = parser.parse(); // Generate the abstract syntax tree (AST)
    } catch (Exception e) {
      // compilation error in eval-string

      // Set the global error variable "$@" using Namespace.setGlobalVariable(key, value)
      Namespace.setGlobalVariable("$@", e.toString());

      ast = new UnaryOperatorNode("undef", null, 1); // return an "undef" ast
    }

    evalCtx.errorUtil = new ErrorMessageUtil(evalCtx.fileName, tokens);
    Class<?> generatedClass =
        EmitterMethodCreator.createClassWithMethod(
            evalCtx, newEnv, // Closure variables
            ast,
            true  // use try-catch
    );
    return generatedClass;
  }

  public String toStringRef() {
    return "CODE(" + this.hashCode() + ")";
  }

  public int getIntRef() {
    return this.hashCode();
  }

  public double getDoubleRef() {
    return this.hashCode();
  }

  public boolean getBooleanRef() {
    return true;
  }
}
