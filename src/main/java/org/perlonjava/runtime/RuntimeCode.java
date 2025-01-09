package org.perlonjava.runtime;

import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.codegen.JavaClassInfo;
import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.parser.Parser;
import org.perlonjava.symbols.ScopedSymbolTable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.SpecialBlock.runUnitcheckBlocks;

/**
 * The RuntimeCode class represents a compiled code object in the runtime environment.
 * It provides functionality to compile, store, and execute Perl subroutines and eval strings.
 */
public class RuntimeCode implements RuntimeScalarReference {

    // Temporary storage for anonymous subroutines and eval string compiler context
    public static HashMap<String, Class<?>> anonSubs = new HashMap<>(); // temp storage for makeCodeObject()
    public static HashMap<String, EmitterContext> evalContext = new HashMap<>(); // storage for eval string compiler context

    // Method object representing the compiled subroutine
    public Method methodObject;
    // Code object instance used during execution
    public Object codeObject;
    // Prototype of the subroutine
    public String prototype;
    // Attributes associated with the subroutine
    public List<String> attributes = new ArrayList<>();

    // State variables
    public Map<String, Boolean> stateVariableInitialized = new HashMap<>();
    public Map<String, RuntimeScalar> stateVariable = new HashMap<>();
    public Map<String, RuntimeArray> stateArray = new HashMap<>();
    public Map<String, RuntimeHash> stateHash = new HashMap<>();

    public RuntimeList constantValue;

    /**
     * Constructs a RuntimeCode instance with the specified prototype and attributes.
     *
     * @param prototype  the prototype of the subroutine
     * @param attributes the attributes associated with the subroutine
     */
    public RuntimeCode(String prototype, List<String> attributes) {
        this.prototype = prototype;
        this.attributes = attributes;
    }

    /**
     * Constructs a RuntimeCode instance with the specified method object and code object.
     *
     * @param methodObject the method object representing the compiled subroutine
     * @param codeObject   the code object instance used during execution
     */
    public RuntimeCode(Method methodObject, Object codeObject) {
        this.methodObject = methodObject;
        this.codeObject = codeObject;
    }

    /**
     * Constructs a RuntimeCode instance with the specified method object, code object, and prototype.
     *
     * @param methodObject the method object representing the compiled subroutine
     * @param codeObject   the code object instance used during execution
     * @param prototype    the prototype of the subroutine
     */
    public RuntimeCode(Method methodObject, Object codeObject, String prototype) {
        this.methodObject = methodObject;
        this.codeObject = codeObject;
        this.prototype = prototype;
    }

    /**
     * Compiles the text of an eval string into a Class that represents an anonymous subroutine.
     * After the Class is returned to the caller, an instance of the Class will be populated
     * with closure variables, and then makeCodeObject() will be called to transform the Class
     * instance into a Perl CODE object.
     *
     * @param code    the RuntimeScalar containing the eval string
     * @param evalTag the tag used to retrieve the eval context
     * @return the compiled Class representing the anonymous subroutine
     * @throws Exception if an error occurs during compilation
     */
    public static Class<?> evalStringHelper(RuntimeScalar code, String evalTag) throws Exception {
        // Retrieve the eval context that was saved at program compile-time
        EmitterContext ctx = RuntimeCode.evalContext.get(evalTag);
        ScopedSymbolTable symbolTable = ctx.symbolTable.snapShot();

        EmitterContext evalCtx = new EmitterContext(
                new JavaClassInfo(),  // internal java class name
                ctx.symbolTable.snapShot(), // symbolTable
                null, // method visitor
                null, // class writer
                ctx.contextType, // call context
                true, // is boxed
                ctx.errorUtil, // error message utility
                ctx.compilerOptions,
                ctx.unitcheckBlocks);
        // evalCtx.logDebug("evalStringHelper EmitterContext: " + evalCtx);
        // evalCtx.logDebug("evalStringHelper Code: " + code);

        // Process the string source code to create the LexerToken list
        Lexer lexer = new Lexer(code.toString());
        List<LexerToken> tokens = lexer.tokenize(); // Tokenize the Perl code
        Node ast;
        Class<?> generatedClass;
        try {
            // Create the AST
            // Create an instance of ErrorMessageUtil with the file name and token list
            evalCtx.errorUtil = new ErrorMessageUtil(evalCtx.compilerOptions.fileName, tokens);
            Parser parser = new Parser(evalCtx, tokens); // Parse the tokens
            ast = parser.parse(); // Generate the abstract syntax tree (AST)

            // Create a new instance of ErrorMessageUtil, resetting the line counter
            evalCtx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
            evalCtx.symbolTable = symbolTable.snapShot(); // reset the symboltable
            generatedClass = EmitterMethodCreator.createClassWithMethod(
                    evalCtx,
                    ast,
                    true  // use try-catch
            );
            runUnitcheckBlocks(ctx.unitcheckBlocks);
        } catch (Exception e) {
            // Compilation error in eval-string

            // Set the global error variable "$@" using GlobalContext.setGlobalVariable(key, value)
            GlobalVariable.getGlobalVariable("main::@").set(e.getMessage());

            // In case of error return an "undef" ast and class
            ast = new OperatorNode("undef", null, 1);
            evalCtx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
            evalCtx.symbolTable = symbolTable.snapShot(); // reset the symboltable
            generatedClass = EmitterMethodCreator.createClassWithMethod(
                    evalCtx,
                    ast,
                    false
            );
        }
        return generatedClass;
    }

    // make sure we return a RuntimeScalar from __SUB__
    public static RuntimeScalar selfReferenceMaybeNull(RuntimeScalar codeRef) {
        return codeRef == null
                ? scalarUndef
                : codeRef;
    }

    /**
     * Factory method to create a CODE object (anonymous subroutine).
     * This is called right after a new Class is compiled.
     * The codeObject is an instance of the new Class, with the closure variables in place.
     *
     * @param codeObject the instance of the compiled Class
     * @return a RuntimeScalar representing the CODE object
     * @throws Exception if an error occurs during method retrieval
     */
    public static RuntimeScalar makeCodeObject(Object codeObject) throws Exception {
        return makeCodeObject(codeObject, null);
    }

    public static RuntimeScalar makeCodeObject(Object codeObject, String prototype) throws Exception {
        // Retrieve the class of the provided code object
        Class<?> clazz = codeObject.getClass();

        // Get the 'apply' method from the class.
        // This method takes RuntimeArray and RuntimeContextType as parameters.
        Method mm = clazz.getMethod("apply", RuntimeArray.class, int.class);

        // Wrap the method and the code object in a RuntimeCode instance
        // This allows us to store both the method and the object it belongs to
        // Create a new RuntimeScalar instance to hold the CODE object
        RuntimeScalar codeRef = new RuntimeScalar(new RuntimeCode(mm, codeObject, prototype));

        // Set the __SUB__ instance field
        Field field = clazz.getDeclaredField("__SUB__");
        field.set(codeObject, codeRef);

        return codeRef;
    }

    /**
     * Call a method in a Perl-like class hierarchy using the C3 linearization algorithm.
     *
     * @param runtimeScalar
     * @param method         The method to resolve.
     * @param currentPackage The package to resolve SUPER::method in.
     * @param args           The arguments to pass to the method.
     * @param callContext    The call context.
     * @return The result of the method call.
     */
    public static RuntimeList call(RuntimeScalar runtimeScalar,
                                   RuntimeScalar method,
                                   String currentPackage,
                                   RuntimeArray args,
                                   int callContext) throws Exception {
        // insert `this` into the parameter list
        args.elements.addFirst(runtimeScalar);

        // System.out.println("call ->" + method + " " + currentPackage + " " + args + " " + callContext);

        if (method.type == RuntimeScalarType.CODE) {
            // If method is a subroutine reference, just call it
            return apply(method, args, callContext);
        }

        String methodName = method.toString();

        // Retrieve Perl class name
        String perlClassName;
        switch (runtimeScalar.type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
                int blessId = ((RuntimeBaseEntity) runtimeScalar.value).blessId;
                if (blessId == 0) {
                    throw new PerlCompilerException("Can't call method \"" + methodName + "\" on unblessed reference");
                }
                perlClassName = NameNormalizer.getBlessStr(blessId);
                break;
            case UNDEF:
                throw new PerlCompilerException("Can't call method \"" + methodName + "\" on an undefined value");
            default:
                perlClassName = runtimeScalar.toString();
                if (perlClassName.isEmpty()) {
                    throw new PerlCompilerException("Can't call method \"" + methodName + "\" on an undefined value");
                }
                if (perlClassName.endsWith("::")) {
                    perlClassName = perlClassName.substring(0, perlClassName.length() - 2);
                }
        }

        // Method name can be:
        // - A short name (e.g., "new")
        // - Fully qualified name
        // - A variable or dereference (e.g., $file->${ \'save' })
        // - "SUPER::name"

        // Class name can be:
        // - A string
        // - STDOUT
        // - A subroutine (e.g., Class->new() is Class()->new() if Class is a subroutine)
        // - Class::->new() is the same as Class->new()

        // System.out.println("call perlClassName: " + perlClassName + " methodName: " + methodName);
        if (methodName.startsWith("SUPER::")) {
            // Remove the "SUPER::" prefix to get the actual method name
            String superMethodName = methodName.substring(7);
            // System.out.println("call SUPER:: " + superMethodName);

            // Get the linearized inheritance hierarchy for the current package
            List<String> linearizedClasses = InheritanceResolver.linearizeC3(currentPackage);

            // Find the index of the current package in the linearized hierarchy
            int currentIndex = linearizedClasses.indexOf(currentPackage);

            // Iterate over the linearized classes starting from the class after the current package
            for (int i = currentIndex + 1; i < linearizedClasses.size(); i++) {
                String className = linearizedClasses.get(i);
                String normalizedClassMethodName = NameNormalizer.normalizeVariableName(superMethodName, className);

                if (GlobalVariable.existsGlobalCodeRef(normalizedClassMethodName)) {
                    // If the method is found, retrieve and apply it
                    RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(normalizedClassMethodName);

                    // Save the method in the cache
                    InheritanceResolver.cacheMethod(normalizedClassMethodName, codeRef);

                    return apply(codeRef, args, callContext);
                }
            }

            // If the method is not found in any class, throw an exception
            throw new PerlCompilerException("Can't locate object method \"" + superMethodName + "\" via package \"" + currentPackage + "\" (perhaps you forgot to load \"" + currentPackage + "\"?)");
        }

        // Check the method cache
        String normalizedMethodName = NameNormalizer.normalizeVariableName(methodName, perlClassName);
        RuntimeScalar cachedMethod = InheritanceResolver.getCachedMethod(normalizedMethodName);
        if (cachedMethod != null) {
            return apply(cachedMethod, args, callContext);
        }

        // Get the linearized inheritance hierarchy using C3
        List<String> linearizedClasses = InheritanceResolver.linearizeC3(perlClassName);

        // Iterate over the linearized classes to find the method
        for (String className : linearizedClasses) {
            String normalizedClassMethodName = NameNormalizer.normalizeVariableName(methodName, className);
            // System.out.println("call normalizedClassMethodName: " + normalizedClassMethodName);
            if (GlobalVariable.existsGlobalCodeRef(normalizedClassMethodName)) {
                // System.out.println("call GlobalVariable.getGlobalCodeRef: " + normalizedClassMethodName);
                // If the method is found, retrieve and apply it
                RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(normalizedClassMethodName);

                // Save the method in the cache
                InheritanceResolver.cacheMethod(normalizedMethodName, codeRef);

                return apply(codeRef, args, callContext);
            }
        }

        // If the method is not found in any class, throw an exception
        throw new PerlCompilerException("Can't locate object method \"" + methodName + "\" via package \"" + perlClassName + "\" (perhaps you forgot to load \"" + perlClassName + "\"?)");
    }

    public static RuntimeList caller(RuntimeList args, int ctx) {
        RuntimeList res = new RuntimeList();
        int frame = 0;
        if (!args.isEmpty()) {
            frame = args.getFirst().getInt();
        }
        CallerStack.CallerInfo info = CallerStack.peek(0);
        if (info == null) {
            // Runtime stack trace
            Throwable t = new Throwable();
            ArrayList<ArrayList<String>> stackTrace = ExceptionFormatter.formatException(t);
            frame++;  // frame 0 is the current method, so we need to increment it
            if (frame >= 0 && frame < stackTrace.size()) {
                if (ctx == RuntimeContextType.SCALAR) {
                    res.add(new RuntimeScalar(stackTrace.get(frame).getFirst()));
                } else {
                    res.add(new RuntimeScalar(stackTrace.get(frame).get(0)));
                    res.add(new RuntimeScalar(stackTrace.get(frame).get(1)));
                    res.add(new RuntimeScalar(stackTrace.get(frame).get(2)));
                }
            }
        } else {
            // Compile-time stack trace
            if (ctx == RuntimeContextType.SCALAR) {
                res.add(new RuntimeScalar(info.packageName));
            } else {
                res.add(new RuntimeScalar(info.packageName));
                res.add(new RuntimeScalar(info.filename));
                res.add(new RuntimeScalar(info.line));
            }
        }
        return res;
    }

    // Method to apply (execute) a subroutine reference
    public static RuntimeList apply(RuntimeScalar runtimeScalar, RuntimeArray a, int callContext) {
        // Check if the type of this RuntimeScalar is CODE
        if (runtimeScalar.type == RuntimeScalarType.CODE) {
            // Cast the value to RuntimeCode and call apply()
            return ((RuntimeCode) runtimeScalar.value).apply(a, callContext);
        } else {
            // If the type is not CODE, throw an exception indicating an invalid state
            throw new PerlCompilerException("Not a CODE reference");
        }
    }

    // Method to apply (execute) a subroutine reference
    public static RuntimeList apply(RuntimeScalar runtimeScalar, String subroutineName, RuntimeArray a, int callContext) {
        // Check if the type of this RuntimeScalar is CODE
        if (runtimeScalar.type == RuntimeScalarType.CODE) {
            // Cast the value to RuntimeCode and call apply()
            return ((RuntimeCode) runtimeScalar.value).apply(subroutineName, a, callContext);
        } else {
            // If the type is not CODE, throw an exception indicating an invalid state

            // Does AUTOLOAD exist?
            if (!subroutineName.isEmpty()) {
                // Check if AUTOLOAD exists
                String autoloadString = subroutineName.substring(0, subroutineName.lastIndexOf("::") + 2) + "AUTOLOAD";
                // System.err.println("AUTOLOAD: " + fullName);
                RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                if (autoload.getDefinedBoolean()) {
                    // System.err.println("AUTOLOAD exists: " + fullName);
                    // Set $AUTOLOAD name
                    getGlobalVariable(autoloadString).set(subroutineName);
                    // Call AUTOLOAD
                    return apply(autoload, a, callContext);
                }
            }

            if (!subroutineName.isEmpty() && runtimeScalar.type == RuntimeScalarType.GLOB && runtimeScalar.value == null) {
                throw new PerlCompilerException("Undefined subroutine &" + subroutineName + " called at ");
            }

            throw new PerlCompilerException("Not a CODE reference");
        }
    }

    // Return a reference to the subroutine with this name: \&$a
    public static RuntimeScalar createCodeReference(RuntimeScalar runtimeScalar, String packageName) {
        String name = NameNormalizer.normalizeVariableName(runtimeScalar.toString(), packageName);
        // System.out.println("Creating code reference: " + name + " got: " + GlobalContext.getGlobalCodeRef(name));
        return GlobalVariable.getGlobalCodeRef(name);
    }

    public static RuntimeScalar prototype(RuntimeScalar runtimeScalar, String packageName) {
        RuntimeScalar code = runtimeScalar;
        if (code.type != RuntimeScalarType.CODE) {
            String name = NameNormalizer.normalizeVariableName(code.toString(), packageName);
            // System.out.println("Looking for prototype: " + name);
            code = GlobalVariable.getGlobalCodeRef(name);
        }
        // System.out.println("type: " + code.type);
        if (code.type == RuntimeScalarType.CODE) {
            // System.out.println("prototype: " + ((RuntimeCode) code.value).prototype);
            return new RuntimeScalar(((RuntimeCode) code.value).prototype);
        }
        return scalarUndef;
    }

    /**
     * Method to apply (execute) a subroutine reference.
     * Invokes the method associated with the code object, passing the RuntimeArray and RuntimeContextType as arguments.
     *
     * @param a           the RuntimeArray containing the arguments for the subroutine
     * @param callContext the context in which the subroutine is called
     * @return the result of the subroutine execution as a RuntimeList
     */
    public RuntimeList apply(RuntimeArray a, int callContext) {
        if (constantValue != null) {
            // Alternative way to create constants like: `$constant::{_CAN_PCS} = \$const`
            return new RuntimeList(constantValue);
        }
        try {
            return (RuntimeList) this.methodObject.invoke(this.codeObject, a, callContext);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public RuntimeList apply(String subroutineName, RuntimeArray a, int callContext) {
        if (constantValue != null) {
            // Alternative way to create constants like: `$constant::{_CAN_PCS} = \$const`
            return new RuntimeList(constantValue);
        }
        try {
            return (RuntimeList) this.methodObject.invoke(this.codeObject, a, callContext);
        } catch (Exception e) {

            if (!subroutineName.isEmpty() && this.methodObject == null) {
                throw new PerlCompilerException("Undefined subroutine &" + subroutineName + " called at ");
            }

            throw new RuntimeException(e);
        }
    }

    /**
     * Returns a string representation of the CODE reference.
     *
     * @return a string representing the CODE reference
     */
    public String toStringRef() {

        // XXX TODO code reference can be blessed
        // return (blessId == 0
        //         ? ref
        //         : NameNormalizer.getBlessStr(blessId) + "=" + ref);

        return "CODE(0x" + Integer.toHexString(this.hashCode()) + ")";
    }

    /**
     * Returns an integer representation of the CODE reference.
     *
     * @return an integer representing the CODE reference
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Returns a double representation of the CODE reference.
     *
     * @return a double representing the CODE reference
     */
    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Returns a boolean representation of the CODE reference.
     *
     * @return true, indicating the presence of the CODE reference
     */
    public boolean getBooleanRef() {
        return true;
    }
}
