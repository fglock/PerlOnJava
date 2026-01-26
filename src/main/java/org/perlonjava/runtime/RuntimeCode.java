package org.perlonjava.runtime;

import org.perlonjava.CompilerOptions;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.codegen.JavaClassInfo;
import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.parser.Parser;
import org.perlonjava.mro.InheritanceResolver;
import org.perlonjava.operators.ModuleOperators;
import org.perlonjava.scriptengine.PerlLanguageProvider;
import org.perlonjava.symbols.ScopedSymbolTable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Supplier;

import static org.perlonjava.Configuration.getPerlVersionNoV;
import static org.perlonjava.parser.ParserTables.CORE_PROTOTYPES;
import static org.perlonjava.runtime.GlobalVariable.*;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.RuntimeScalarType.*;
import static org.perlonjava.runtime.SpecialBlock.runEndBlocks;
import static org.perlonjava.parser.SpecialBlockParser.setCurrentScope;
import static org.perlonjava.runtime.SpecialBlock.runUnitcheckBlocks;

/**
 * The RuntimeCode class represents a compiled code object in the runtime environment.
 * It provides functionality to compile, store, and execute Perl subroutines and eval strings.
 */
public class RuntimeCode extends RuntimeBase implements RuntimeScalarReference {

    // Lookup object for performing method handle operations
    public static final MethodHandles.Lookup lookup = MethodHandles.lookup();
    // Cache for memoization of evalStringHelper results
    private static final int CLASS_CACHE_SIZE = 100;
    private static final Map<String, Class<?>> evalCache = new LinkedHashMap<String, Class<?>>(CLASS_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Class<?>> eldest) {
            return size() > CLASS_CACHE_SIZE;
        }
    };
    // Cache for method handles with eviction policy
    private static final int METHOD_HANDLE_CACHE_SIZE = 100;
    private static final Map<Class<?>, MethodHandle> methodHandleCache = new LinkedHashMap<Class<?>, MethodHandle>(METHOD_HANDLE_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Class<?>, MethodHandle> eldest) {
            return size() > METHOD_HANDLE_CACHE_SIZE;
        }
    };
    public static MethodType methodType = MethodType.methodType(RuntimeList.class, RuntimeArray.class, int.class);
    // Temporary storage for anonymous subroutines and eval string compiler context
    public static HashMap<String, Class<?>> anonSubs = new HashMap<>(); // temp storage for makeCodeObject()
    public static HashMap<String, EmitterContext> evalContext = new HashMap<>(); // storage for eval string compiler context
    // Runtime eval counter for generating unique filenames when $^P is set
    private static int runtimeEvalCounter = 1;
    // Method object representing the compiled subroutine
    public MethodHandle methodHandle;
    public boolean isStatic;
    public String autoloadVariableName = null;
    // Code object instance used during execution
    public Object codeObject;
    // Prototype of the subroutine
    public String prototype;
    // Attributes associated with the subroutine
    public List<String> attributes = new ArrayList<>();
    // Method context information for next::method support
    public String packageName;
    public String subName;
    // Source package for imported forward declarations (used for AUTOLOAD resolution)
    public String sourcePackage = null;
    // Flag to indicate this is a symbolic reference created by \&{string} that should always be "defined"
    public boolean isSymbolicReference = false;
    // Flag to indicate this is a built-in operator
    public boolean isBuiltin = false;
    // State variables
    public Map<String, Boolean> stateVariableInitialized = new HashMap<>();
    public Map<String, RuntimeScalar> stateVariable = new HashMap<>();
    public Map<String, RuntimeArray> stateArray = new HashMap<>();
    public Map<String, RuntimeHash> stateHash = new HashMap<>();
    public RuntimeList constantValue;
    // Field to hold the thread compiling this code
    public Supplier<Void> compilerSupplier;

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

    public RuntimeCode(MethodHandle methodObject, Object codeObject, String prototype) {
        this.methodHandle = methodObject;
        this.codeObject = codeObject;
        this.prototype = prototype;
    }

    // Add a method to clear caches when globals are reset
    public static void clearCaches() {
        evalCache.clear();
        methodHandleCache.clear();
        anonSubs.clear();
    }

    public static void copy(RuntimeCode code, RuntimeCode codeFrom) {
        code.prototype = codeFrom.prototype;
        code.attributes = codeFrom.attributes;
        code.methodHandle = codeFrom.methodHandle;
        code.isStatic = codeFrom.isStatic;
        code.codeObject = codeFrom.codeObject;
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

        // Check if the eval string contains non-ASCII characters
        // If so, treat it as Unicode source to preserve Unicode characters during parsing
        // EXCEPT for evalbytes, which must treat everything as bytes
        String evalString = code.toString();
        boolean hasUnicode = false;
        if (!ctx.isEvalbytes && code.type != RuntimeScalarType.BYTE_STRING) {
            for (int i = 0; i < evalString.length(); i++) {
                if (evalString.charAt(i) > 127) {
                    hasUnicode = true;
                    break;
                }
            }
        }

        // Clone compiler options and set isUnicodeSource if needed
        // This only affects string parsing, not symbol table or method resolution
        CompilerOptions evalCompilerOptions = ctx.compilerOptions;
        // The eval string can originate from either a Perl STRING or BYTE_STRING scalar.
        // For BYTE_STRING source we must treat the source as raw bytes (latin-1-ish) and
        // NOT re-encode characters to UTF-8 when simulating 'non-unicode source'.
        boolean isByteStringSource = !ctx.isEvalbytes && code.type == RuntimeScalarType.BYTE_STRING;
        if (hasUnicode || ctx.isEvalbytes || isByteStringSource) {
            evalCompilerOptions = (CompilerOptions) ctx.compilerOptions.clone();
            if (hasUnicode) {
                evalCompilerOptions.isUnicodeSource = true;
            }
            if (ctx.isEvalbytes) {
                evalCompilerOptions.isEvalbytes = true;
            }
            if (isByteStringSource) {
                evalCompilerOptions.isByteStringSource = true;
            }
        }

        // Check $^P to determine if we should use caching
        // When debugging is enabled, we want each eval to get a unique filename
        int debugFlags = GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("P")).getInt();
        boolean isDebugging = debugFlags != 0;
        
        // Override the filename with a runtime-generated eval number when debugging
        String actualFileName = evalCompilerOptions.fileName;
        if (isDebugging) {
            synchronized (RuntimeCode.class) {
                actualFileName = "(eval " + runtimeEvalCounter++ + ")";
            }
        }
        
        // Check if the result is already cached (include hasUnicode, isEvalbytes, byte-string-source, and feature flags in cache key)
        // Skip caching when $^P is set, so each eval gets a unique filename
        int featureFlags = ctx.symbolTable.featureFlagsStack.peek();
        String cacheKey = code.toString() + '\0' + evalTag + '\0' + hasUnicode + '\0' + ctx.isEvalbytes + '\0' + isByteStringSource + '\0' + featureFlags;
        Class<?> cachedClass = null;
        if (!isDebugging) {
            synchronized (evalCache) {
                if (evalCache.containsKey(cacheKey)) {
                    cachedClass = evalCache.get(cacheKey);
                }
            }
            
            if (cachedClass != null) {
                return cachedClass;
            }
        }

        // IMPORTANT: The eval call site (EmitEval) computes the constructor signature from
        // ctx.symbolTable (captured at compile-time). We must use that exact symbol table for
        // codegen, otherwise the generated <init>(...) descriptor may not match what the
        // call site is looking up via reflection.
        ScopedSymbolTable capturedSymbolTable = ctx.symbolTable;

        // eval may include lexical pragmas (use strict/warnings/features). We need those flags
        // during codegen of the eval body, but they must NOT leak back into the caller scope.
        BitSet savedWarningFlags = (BitSet) capturedSymbolTable.warningFlagsStack.peek().clone();
        int savedFeatureFlags = capturedSymbolTable.featureFlagsStack.peek();
        int savedStrictOptions = capturedSymbolTable.strictOptionsStack.peek();

        // Parse using a mutable clone so lexical declarations inside the eval do not
        // change the captured environment / constructor signature.
        // IMPORTANT: The parseSymbolTable starts with the captured flags so that
        // the eval code is parsed with the correct feature/strict/warning context
        ScopedSymbolTable parseSymbolTable = capturedSymbolTable.snapShot();

        EmitterContext evalCtx = new EmitterContext(
                new JavaClassInfo(),  // internal java class name
                parseSymbolTable, // symbolTable
                null, // method visitor
                null, // class writer
                ctx.contextType, // call context
                true, // is boxed
                ctx.errorUtil, // error message utility
                evalCompilerOptions, // possibly modified for Unicode source
                ctx.unitcheckBlocks);
        // evalCtx.logDebug("evalStringHelper EmitterContext: " + evalCtx);
        // evalCtx.logDebug("evalStringHelper Code: " + code);

        // Process the string source code to create the LexerToken list
        Lexer lexer = new Lexer(evalString);
        List<LexerToken> tokens = lexer.tokenize(); // Tokenize the Perl code
        Node ast;
        Class<?> generatedClass;
        try {
            // Create the AST
            // Create an instance of ErrorMessageUtil with the file name and token list
            evalCtx.errorUtil = new ErrorMessageUtil(evalCtx.compilerOptions.fileName, tokens);
            Parser parser = new Parser(evalCtx, tokens); // Parse the tokens
            ast = parser.parse(); // Generate the abstract syntax tree (AST)

            // ast = ConstantFoldingVisitor.foldConstants(ast);

            // Create a new instance of ErrorMessageUtil, resetting the line counter
            evalCtx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
            ScopedSymbolTable postParseSymbolTable = evalCtx.symbolTable;
            evalCtx.symbolTable = capturedSymbolTable;
            evalCtx.symbolTable.copyFlagsFrom(postParseSymbolTable);
            setCurrentScope(evalCtx.symbolTable);
            
            // Use the captured environment array from compile-time to ensure
            // constructor signature matches what EmitEval generated bytecode for
            if (ctx.capturedEnv != null) {
                evalCtx.capturedEnv = ctx.capturedEnv;
            }
            
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
            evalCtx.symbolTable = capturedSymbolTable;
            setCurrentScope(evalCtx.symbolTable);
            generatedClass = EmitterMethodCreator.createClassWithMethod(
                    evalCtx,
                    ast,
                    false
            );
        } finally {
            // Restore caller lexical flags (do not leak eval pragmas).
            capturedSymbolTable.warningFlagsStack.pop();
            capturedSymbolTable.warningFlagsStack.push((BitSet) savedWarningFlags.clone());

            capturedSymbolTable.featureFlagsStack.pop();
            capturedSymbolTable.featureFlagsStack.push(savedFeatureFlags);

            capturedSymbolTable.strictOptionsStack.pop();
            capturedSymbolTable.strictOptionsStack.push(savedStrictOptions);

            setCurrentScope(capturedSymbolTable);
        }

        // Cache the result (unless debugging is enabled)
        if (!isDebugging) {
            synchronized (evalCache) {
                evalCache.put(cacheKey, generatedClass);
            }
        }

        // Store source lines in symbol table if $^P flags are set
        storeSourceLines(evalString, actualFileName, ast);

        return generatedClass;
    }

    /**
     * Stores source lines in the symbol table for debugger support when $^P flags are set.
     *
     * @param evalString The source code string to store
     * @param filename   The filename (e.g., "(eval 1)")
     * @param ast        The AST to check for subroutine definitions
     */
    private static void storeSourceLines(String evalString, String filename, Node ast) {
        // Check $^P for debugger flags
        int debugFlags = GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("P")).getInt();
        // 0x02 (2): Line-by-line debugging (also saves source like 0x400)
        // 0x400 (1024): Save source code lines
        // 0x800 (2048): Include evals that generate no subroutines
        // 0x1000 (4096): Include source that did not compile
        boolean shouldSaveSource = (debugFlags & 0x02) != 0 || (debugFlags & 0x400) != 0;
        boolean saveWithoutSubs = (debugFlags & 0x800) != 0;
        
        if (shouldSaveSource) {
            // Note: We can't reliably detect subroutine definitions from the AST because
            // subroutines are processed at parse-time and removed from the AST.
            // Use a simple heuristic: check if the eval string contains "sub " followed by
            // an identifier or block.
            boolean definesSubs = evalString.matches("(?s).*\\bsub\\s+(?:\\w+|\\{).*");
            
            // Only save if either:
            // - The eval defines subroutines, OR
            // - The 0x800 flag is set (save evals without subs)
            if (!definesSubs && !saveWithoutSubs) {
                return;  // Skip this eval
            }
            // Store in the symbol table as @{"_<(eval N)"}
            String symbolKey = "_<" + filename;
            
            // Split the eval string into lines (without including trailing empty strings)
            String[] lines = evalString.split("\n");
            
            // Create the array with the format expected by the debugger:
            // [0] = undef, [1..n] = lines with \n, [n+1] = \n, [n+2] = ;
            String arrayKey = "main::" + symbolKey;
            RuntimeArray sourceArray = GlobalVariable.getGlobalArray(arrayKey);
            sourceArray.elements.clear();
            
            // Index 0: undef
            sourceArray.elements.add(RuntimeScalarCache.scalarUndef);
            
            // Indexes 1..n: each line with "\n" appended
            for (String line : lines) {
                sourceArray.elements.add(new RuntimeScalar(line + "\n"));
            }
            
            // Index n+1: "\n"
            sourceArray.elements.add(new RuntimeScalar("\n"));
            
            // Index n+2: ";"
            sourceArray.elements.add(new RuntimeScalar(";"));
        }
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

        // Check if the method handle is already cached
        MethodHandle methodHandle;
        synchronized (methodHandleCache) {
            if (methodHandleCache.containsKey(clazz)) {
                methodHandle = methodHandleCache.get(clazz);
            } else {
                // Get the 'apply' method from the class.
                methodHandle = RuntimeCode.lookup.findVirtual(clazz, "apply", RuntimeCode.methodType);
                // Cache the method handle
                methodHandleCache.put(clazz, methodHandle);
            }
        }

        // Wrap the method and the code object in a RuntimeCode instance
        // This allows us to store both the method and the object it belongs to
        // Create a new RuntimeScalar instance to hold the CODE object
        RuntimeScalar codeRef = new RuntimeScalar(new RuntimeCode(methodHandle, codeObject, prototype));

        // Set the __SUB__ instance field
        Field field = clazz.getDeclaredField("__SUB__");
        field.set(codeObject, codeRef);

        return codeRef;
    }

    /**
     * Call a method in a Perl-like class hierarchy using the C3 linearization algorithm.
     * This version accepts a native RuntimeBase[] array for parameters.
     *
     * @param runtimeScalar The object to call the method on.
     * @param method        The method to resolve.
     * @param currentSub    The subroutine to resolve SUPER::method in.
     * @param args          The arguments to pass to the method as native array.
     * @param callContext   The call context.
     * @return The result of the method call.
     */
    public static RuntimeList call(RuntimeScalar runtimeScalar,
                                   RuntimeScalar method,
                                   RuntimeScalar currentSub,
                                   RuntimeBase[] args,
                                   int callContext) {
        // Transform the native array to RuntimeArray of aliases (Perl variable `@_`)
        // Note: `this` (runtimeScalar) will be inserted by the RuntimeArray version
        RuntimeArray a = new RuntimeArray();
        for (RuntimeBase arg : args) {
            arg.setArrayOfAlias(a);
        }
        return call(runtimeScalar, method, currentSub, a, callContext);
    }

    /**
     * Call a method in a Perl-like class hierarchy using the C3 linearization algorithm.
     *
     * @param runtimeScalar The object to call the method on.
     * @param method        The method to resolve.
     * @param currentSub    The subroutine to resolve SUPER::method in.
     * @param args          The arguments to pass to the method.
     * @param callContext   The call context.
     * @return The result of the method call.
     */
    public static RuntimeList call(RuntimeScalar runtimeScalar,
                                   RuntimeScalar method,
                                   RuntimeScalar currentSub,
                                   RuntimeArray args,
                                   int callContext) {
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

        if (RuntimeScalarType.isReference(runtimeScalar)) {
            // Handle all reference types (REFERENCE, ARRAYREFERENCE, HASHREFERENCE, etc.)
            int blessId = ((RuntimeBase) runtimeScalar.value).blessId;
            if (blessId == 0) {
                if (runtimeScalar.type == GLOBREFERENCE) {
                    // Auto-bless file handler to IO::File which inherits from both IO::Handle and IO::Seekable
                    // This allows GLOBs to call methods like seek, tell, etc.
                    perlClassName = "IO::File";
                    // Load the module if needed
                    // TODO - optimize by creating a flag in RuntimeIO
                    ModuleOperators.require(new RuntimeScalar("IO/File.pm"));
                } else {
                    // Not auto-blessed
                    throw new PerlCompilerException("Can't call method \"" + methodName + "\" on unblessed reference");
                }
            } else {
                perlClassName = NameNormalizer.getBlessStr(blessId);
            }
        } else if (runtimeScalar.type == UNDEF) {
            throw new PerlCompilerException("Can't call method \"" + methodName + "\" on an undefined value");
        } else {
            perlClassName = runtimeScalar.toString();
            if (perlClassName.isEmpty()) {
                throw new PerlCompilerException("Can't call method \"" + methodName + "\" on an undefined value");
            }
            if (perlClassName.endsWith("::")) {
                perlClassName = perlClassName.substring(0, perlClassName.length() - 2);
            }
            if (perlClassName.startsWith("::")) {
                perlClassName = perlClassName.substring(2);
            }
            if (perlClassName.startsWith("main::")) {
                perlClassName = perlClassName.substring(6);
            }
            if (perlClassName.isEmpty()) {
                // Nothing left
                perlClassName = "main";
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
        // - Class->Other::new() fully qualified method name

        // System.out.println("call perlClassName: " + perlClassName + " methodName: " + methodName);

        if (methodName.contains("::")) {

            // Handle next::method calls
            if (methodName.equals("next::method")) {
                return NextMethod.nextMethodWithContext(args, currentSub, callContext);
            }

            // Handle next::can calls
            if (methodName.equals("next::can")) {
                return NextMethod.nextCanWithContext(args, currentSub, callContext);
            }

            // Handle maybe::next::method calls
            if (methodName.equals("maybe::next::method")) {
                return NextMethod.maybeNextMethodWithContext(args, currentSub, callContext);
            }

            // Handle SUPER::method calls
            if (methodName.startsWith("SUPER::")) {
                method = NextMethod.superMethod(currentSub, methodName);
            } else {
                // Fully qualified method name - call the exact subroutine
                method = GlobalVariable.getGlobalCodeRef(methodName);
                if (!method.getDefinedBoolean()) {
                    throw new PerlCompilerException("Undefined subroutine &" + methodName + " called");
                }
            }
        } else {
            // Regular method lookup through inheritance
            if ("__ANON__".equals(perlClassName)) {
                throw new PerlCompilerException("Can't use anonymous symbol table for method lookup");
            }
            method = InheritanceResolver.findMethodInHierarchy(methodName, perlClassName, null, 0);
        }

        if (method != null) {
            // System.out.println("call ->" + method + " " + currentPackage + " " + args + " AUTOLOAD: " + ((RuntimeCode) method.value).autoloadVariableName);

            String autoloadVariableName = ((RuntimeCode) method.value).autoloadVariableName;
            if (autoloadVariableName != null) {
                // The inherited method is an autoloaded subroutine
                // Set the $AUTOLOAD variable to the name of the method that was called

                // Extract class name from "ClassName::AUTOLOAD"
                String className = autoloadVariableName.substring(0, autoloadVariableName.lastIndexOf("::"));
                // Make fully qualified method name
                String fullMethodName = NameNormalizer.normalizeVariableName(methodName, className);
                // Set the $AUTOLOAD variable to the fully qualified name of the method
                getGlobalVariable(autoloadVariableName).set(fullMethodName);
            }

            return apply(method, args, callContext);
        }

        // If the method is not found in any class, handle special cases
        // 'import' is special in Perl - it should not throw an exception
        if (methodName.equals("import")) {
            return new RuntimeScalar().getList();
        } else {
            String errorMethodName = methodName;
            // For SUPER:: calls, strip the prefix for error reporting to match Perl behavior
            if (methodName.startsWith("SUPER::")) {
                errorMethodName = methodName.substring(7);
            }
            throw new PerlCompilerException("Can't locate object method \"" + errorMethodName + "\" via package \"" + perlClassName + "\" (perhaps you forgot to load \"" + perlClassName + "\"?)");
        }
    }

    public static RuntimeList caller(RuntimeList args, int ctx) {
        RuntimeList res = new RuntimeList();
        int frame = 0;
        if (!args.isEmpty()) {
            frame = args.getFirst().getInt();
        }

        Throwable t = new Throwable();
        ArrayList<ArrayList<String>> stackTrace = ExceptionFormatter.formatException(t);
        int stackTraceSize = stackTrace.size();

        // Skip the first frame which is the caller() builtin itself
        if (stackTraceSize > 0) {
            frame++;
        }

//        // Show debug info
//        System.err.println("# Runtime stack trace: frame=" + frame + " size=" + stackTraceSize);
//        for (int i = 0; i < stackTraceSize; i++) {
//            ArrayList<String> entry = stackTrace.get(i);
//            String subName = entry.size() > 3 ? entry.get(3) : "NO_SUB";
//            System.err.println("#   " + i + ": pkg=" + entry.get(0) + " file=" + entry.get(1) + " line=" + entry.get(2) + " sub=" + subName);
//        }
//        System.err.println();

        if (frame >= 0 && frame < stackTraceSize) {
            // Runtime stack trace
            if (ctx == RuntimeContextType.SCALAR) {
                res.add(new RuntimeScalar(stackTrace.get(frame).getFirst()));
            } else {
                ArrayList<String> frameInfo = stackTrace.get(frame);
                res.add(new RuntimeScalar(frameInfo.get(0)));  // package
                res.add(new RuntimeScalar(frameInfo.get(1)));  // filename
                res.add(new RuntimeScalar(frameInfo.get(2)));  // line
                
                // The subroutine name at frame N is actually stored at frame N-1
                // because it represents the sub that IS CALLING frame N
                String subName = null;
                if (frame > 0 && frame - 1 < stackTraceSize) {
                    ArrayList<String> prevFrame = stackTrace.get(frame - 1);
                    if (prevFrame.size() > 3) {
                        subName = prevFrame.get(3);
                    }
                }
                
                if (subName != null && !subName.isEmpty()) {
                    res.add(new RuntimeScalar(subName));  // subroutine
                } else {
                    // If no subroutine name or empty, add undef
                    res.add(RuntimeScalarCache.scalarUndef);
                }
                // TODO: Add more caller() return values:
                // hasargs, wantarray, evaltext, is_require, hints, bitmask, hinthash
            }
        }
        return res;
    }

    // Method to apply (execute) a subroutine reference
    public static RuntimeList apply(RuntimeScalar runtimeScalar, RuntimeArray a, int callContext) {
        // Check if the type of this RuntimeScalar is CODE
        if (runtimeScalar.type == RuntimeScalarType.CODE) {
            RuntimeCode code = (RuntimeCode) runtimeScalar.value;
            // Check if it's an unfilled forward declaration (not defined)
            if (!code.defined()) {
                // Try to find AUTOLOAD for this subroutine
                String subroutineName = code.packageName + "::" + code.subName;
                if (code.packageName != null && code.subName != null && !subroutineName.isEmpty()) {
                    // If this is an imported forward declaration, check AUTOLOAD in the source package FIRST
                    // This matches Perl semantics where imported subs resolve via the exporting package's AUTOLOAD
                    if (code.sourcePackage != null && !code.sourcePackage.equals(code.packageName)) {
                        String sourceAutoloadString = code.sourcePackage + "::AUTOLOAD";
                        RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
                        if (sourceAutoload.getDefinedBoolean()) {
                            // Set $AUTOLOAD name to the original package function name
                            String sourceSubroutineName = code.sourcePackage + "::" + code.subName;
                            getGlobalVariable(sourceAutoloadString).set(sourceSubroutineName);
                            // Call AUTOLOAD from the source package
                            return apply(sourceAutoload, a, callContext);
                        }
                    }
                    
                    // Then check if AUTOLOAD exists in the current package
                    String autoloadString = code.packageName + "::AUTOLOAD";
                    RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                    if (autoload.getDefinedBoolean()) {
                        // Set $AUTOLOAD name
                        getGlobalVariable(autoloadString).set(subroutineName);
                        // Call AUTOLOAD
                        return apply(autoload, a, callContext);
                    }
                }
                throw new PerlCompilerException("Undefined subroutine &" + subroutineName + " called at ");
            }
            // Cast the value to RuntimeCode and call apply()
            return code.apply(a, callContext);
        }

        RuntimeScalar overloadedCode = handleCodeOverload(runtimeScalar);
        if (overloadedCode != null) {
            return apply(overloadedCode, a, callContext);
        }

        // If the type is not CODE, throw an exception indicating an invalid state
        throw new PerlCompilerException("Not a CODE reference");
    }

    private static RuntimeScalar handleCodeOverload(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        if (blessId != 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(&{}", new RuntimeArray(runtimeScalar));
                // If the subroutine returns the object itself then it will not be called again
                if (result != null && result.value.hashCode() != runtimeScalar.value.hashCode()) {
                    return result;
                }
            }
        }
        return null;
    }

    // Method to apply (execute) a subroutine reference using native array for parameters
    public static RuntimeList apply(RuntimeScalar runtimeScalar, String subroutineName, RuntimeBase[] args, int callContext) {
        // WORKAROUND for eval-defined subs not filling lexical forward declarations:
        // If the RuntimeScalar is undef (forward declaration never filled), 
        // silently return undef so tests can continue running.
        // This is a temporary workaround for the architectural limitation that eval 
        // contexts are captured at compile time.
        if (runtimeScalar.type == RuntimeScalarType.UNDEF) {
            // Return undef in appropriate context
            if (callContext == RuntimeContextType.LIST) {
                return new RuntimeList();
            } else {
                return new RuntimeList(new RuntimeScalar());
            }
        }
        
        // Check if the type of this RuntimeScalar is CODE
        if (runtimeScalar.type == RuntimeScalarType.CODE) {

            // Transform the native array to RuntimeArray of aliases (Perl variable `@_`)
            RuntimeArray a = new RuntimeArray();
            for (RuntimeBase arg : args) {
                arg.setArrayOfAlias(a);
            }

            RuntimeCode code = (RuntimeCode) runtimeScalar.value;
            if (code.defined()) {
                // Cast the value to RuntimeCode and call apply()
                return code.apply(subroutineName, a, callContext);
            }

            // Does AUTOLOAD exist?
            // If subroutineName is empty, construct it from the RuntimeCode's package and sub name
            String fullSubName = subroutineName;
            if (fullSubName.isEmpty() && code.packageName != null && code.subName != null) {
                fullSubName = code.packageName + "::" + code.subName;
            }
            
            if (!fullSubName.isEmpty()) {
                // If this is an imported forward declaration, check AUTOLOAD in the source package FIRST
                // This matches Perl semantics where imported subs resolve via the exporting package's AUTOLOAD
                if (code.sourcePackage != null && !code.sourcePackage.isEmpty()) {
                    String sourceAutoloadString = code.sourcePackage + "::AUTOLOAD";
                    RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
                    if (sourceAutoload.getDefinedBoolean()) {
                        // Set $AUTOLOAD name to the original package function name
                        String sourceSubroutineName = code.sourcePackage + "::" + code.subName;
                        getGlobalVariable(sourceAutoloadString).set(sourceSubroutineName);
                        // Call AUTOLOAD from the source package
                        return apply(sourceAutoload, a, callContext);
                    }
                }
                
                // Then check if AUTOLOAD exists in the current package
                String autoloadString = fullSubName.substring(0, fullSubName.lastIndexOf("::") + 2) + "AUTOLOAD";
                RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                if (autoload.getDefinedBoolean()) {
                    // Set $AUTOLOAD name
                    getGlobalVariable(autoloadString).set(fullSubName);
                    // Call AUTOLOAD
                    return apply(autoload, a, callContext);
                }
                throw new PerlCompilerException("Undefined subroutine &" + fullSubName + " called at ");
            }
        }

        RuntimeScalar overloadedCode = handleCodeOverload(runtimeScalar);
        if (overloadedCode != null) {
            return apply(overloadedCode, subroutineName, args, callContext);
        }

        throw new PerlCompilerException("Not a CODE reference");
    }

    // Method to apply (execute) a subroutine reference (legacy method for compatibility)
    public static RuntimeList apply(RuntimeScalar runtimeScalar, String subroutineName, RuntimeBase list, int callContext) {
        
        // WORKAROUND for eval-defined subs not filling lexical forward declarations:
        // If the RuntimeScalar is undef (forward declaration never filled), 
        // silently return undef so tests can continue running.
        // This is a temporary workaround for the architectural limitation that eval 
        // contexts are captured at compile time.
        if (runtimeScalar.type == RuntimeScalarType.UNDEF) {
            // Return undef in appropriate context
            if (callContext == RuntimeContextType.LIST) {
                return new RuntimeList();
            } else {
                return new RuntimeList(new RuntimeScalar());
            }
        }
        
        // Check if the type of this RuntimeScalar is CODE
        if (runtimeScalar.type == RuntimeScalarType.CODE) {

            // Transform the value in the stack to RuntimeArray of aliases (Perl variable `@_`)
            RuntimeArray a = list.getArrayOfAlias();

            RuntimeCode code = (RuntimeCode) runtimeScalar.value;
            if (code.defined()) {
                // Cast the value to RuntimeCode and call apply()
                return code.apply(subroutineName, a, callContext);
            }

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
                throw new PerlCompilerException("Undefined subroutine &" + subroutineName + " called at ");
            }
        }

        RuntimeScalar overloadedCode = handleCodeOverload(runtimeScalar);
        if (overloadedCode != null) {
            return apply(overloadedCode, subroutineName, list, callContext);
        }

        throw new PerlCompilerException("Not a CODE reference");
    }

    // Handle \$var where $var might be a CODE reference (for lexical subs)
    // If the value is a CODE reference, return it directly
    // Otherwise, create a scalar reference to it
    public static RuntimeScalar maybeUnwrapCodeReference(RuntimeBase base) {
        if (base instanceof RuntimeScalar scalar) {
            // If it's already a CODE reference, return it directly
            // This handles \&foo where foo is a lexical sub
            if (scalar.type == RuntimeScalarType.CODE) {
                return scalar;
            }
        }
        // For all other cases, create a normal reference
        return base.createReference();
    }
    
    // Return a reference to the subroutine with this name: \&$a
    public static RuntimeScalar createCodeReference(RuntimeScalar runtimeScalar, String packageName) {
        // Special case: if the scalar already contains a CODE reference (lexical sub hidden variable),
        // just return it directly
        if (runtimeScalar.type == RuntimeScalarType.CODE) {
            // Ensure the subroutine is fully compiled before returning the reference
            // This is important for compile-time usage (e.g., use overload qr => \&lexical_sub)
            RuntimeCode code = (RuntimeCode) runtimeScalar.value;
            if (code.compilerSupplier != null) {
                code.compilerSupplier.get(); // Wait for compilation to finish
            }
            return runtimeScalar;
        }
        
        String name = NameNormalizer.normalizeVariableName(runtimeScalar.toString(), packageName);
        // System.out.println("Creating code reference: " + name + " got: " + GlobalContext.getGlobalCodeRef(name));
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(name);

        // Check if this is a constant subroutine
        if (codeRef.type == RuntimeScalarType.CODE && codeRef.value instanceof RuntimeCode runtimeCode) {
            // Mark this as a symbolic reference created by \&{string} pattern
            // This ensures defined(\&{nonexistent}) returns true to match standard Perl behavior
            runtimeCode.isSymbolicReference = true;
            
            // For constant subroutines, return a reference to the constant value
            if (runtimeCode.constantValue != null && !runtimeCode.constantValue.isEmpty()) {
                RuntimeScalar constValue = runtimeCode.constantValue.getFirst();
                return constValue.createReference();
            }
        }

        return codeRef;
    }

    public static RuntimeScalar prototype(RuntimeScalar runtimeScalar, String packageName) {
        RuntimeScalar code = runtimeScalar;
        if (code.type != RuntimeScalarType.CODE) {
            String name = NameNormalizer.normalizeVariableName(code.toString(), packageName);
            // System.out.println("Looking for prototype: " + name);

            if (name.startsWith("CORE::")) {
                String key = name.substring(6);
                if (!CORE_PROTOTYPES.containsKey(key)) {
                    throw new PerlCompilerException("Can't find an opnumber for \"" + key + "\"");
                }
                return new RuntimeScalar(CORE_PROTOTYPES.get(key));
            }

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
     * Gets the current package name using caller() information
     *
     * @return The current package name with "::" suffix
     */
    public static String getCurrentPackage() {
        // Use caller() to get the current package
        RuntimeList callerInfo = caller(new RuntimeList(), RuntimeContextType.LIST);

        if (!callerInfo.isEmpty()) {
            String packageName = callerInfo.getFirst().toString();
            // Ensure it ends with "::" for prefix matching
            return packageName.endsWith("::") ? packageName : packageName + "::";
        }

        // Fallback to main package if caller info is not available
        return "main::";
    }

    public boolean defined() {
        // Symbolic references created by \&{string} are always considered "defined" to match standard Perl
        if (this.isSymbolicReference) {
            return true;
        }
        // Built-in operators are always considered "defined"
        if (this.isBuiltin) {
            return true;
        }
        return this.constantValue != null || this.compilerSupplier != null || this.methodHandle != null;
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
            // Wait for the compilerThread to finish if it exists
            if (this.compilerSupplier != null) {
                // System.out.println("Waiting for compiler thread to finish...");
                this.compilerSupplier.get(); // Wait for the task to finish
            }

            if (isStatic) {
                return (RuntimeList) this.methodHandle.invoke(a, callContext);
            } else {
                return (RuntimeList) this.methodHandle.invoke(this.codeObject, a, callContext);
            }
        } catch (NullPointerException e) {

            if (this.methodHandle == null) {
                throw new PerlCompilerException("Subroutine exists but has null method handle (possible compilation or registration error) at ");
            } else if (this.codeObject == null && !isStatic) {
                throw new PerlCompilerException("Subroutine exists but has null code object at ");
            } else {
                // Original NPE from somewhere else
                throw new PerlCompilerException("Null pointer exception in subroutine call: " + e.getMessage() + " at ");
            }

            //throw new PerlCompilerException("Undefined subroutine called at ");
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            if (!(targetException instanceof RuntimeException)) {
                throw new RuntimeException(targetException);
            }
            throw (RuntimeException) targetException;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public RuntimeList apply(String subroutineName, RuntimeArray a, int callContext) {
        if (constantValue != null) {
            // Alternative way to create constants like: `$constant::{_CAN_PCS} = \$const`
            return new RuntimeList(constantValue);
        }
        try {
            // Wait for the compilerThread to finish if it exists
            if (this.compilerSupplier != null) {
                // System.out.println("Waiting for compiler thread to finish...");
                this.compilerSupplier.get(); // Wait for the task to finish
            }

            if (isStatic) {
                return (RuntimeList) this.methodHandle.invoke(a, callContext);
            } else {
                return (RuntimeList) this.methodHandle.invoke(this.codeObject, a, callContext);
            }
        } catch (NullPointerException e) {
            throw new PerlCompilerException("Undefined subroutine &" + subroutineName + " called at ");
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            if (!(targetException instanceof RuntimeException)) {
                throw new RuntimeException(targetException);
            }
            throw (RuntimeException) targetException;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns a string representation of the CODE reference.
     *
     * @return a string representing the CODE reference
     */
    public String toStringRef() {
        String ref = "CODE(0x" + Integer.toHexString(this.hashCode()) + ")";
        return (blessId == 0
                ? ref
                : NameNormalizer.getBlessStr(blessId) + "=" + ref);
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

    // Get the Scalar alias into an Array
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        arr.elements.add(new RuntimeScalar(this));
        return arr;
    }

    public int countElements() {
        return 1;
    }

    public RuntimeList getList() {
        return new RuntimeList(this);
    }

    public RuntimeScalar scalar() {
        return new RuntimeScalar(this);
    }

    public boolean getBoolean() {
        return true;
    }

    public boolean getDefinedBoolean() {
        return true;
    }

    public RuntimeScalar createReference() {
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.REFERENCE;  // Fixed: should be REFERENCE, not CODE
        result.value = this;
        return result;
    }

    public void addToArray(RuntimeArray array) {
        List<RuntimeScalar> elements = array.elements;
        elements.add(new RuntimeScalar(this));
    }

    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this);
    }

    public Iterator<RuntimeScalar> iterator() {
        return this.scalar().iterator();
    }

    public RuntimeArray setFromList(RuntimeList value) {
        throw new PerlCompilerException("Can't modify constant item in list assignment");
    }

    public RuntimeArray keys() {
        throw new PerlCompilerException("Type of arg 1 to keys must be hash or array");
    }

    public RuntimeArray values() {
        throw new PerlCompilerException("Type of arg 1 to values must be hash or array");
    }

    public RuntimeList each(int ctx) {
        throw new PerlCompilerException("Type of arg 1 to each must be hash or array");
    }

    public RuntimeScalar chop() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

    public RuntimeScalar chomp() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

    public RuntimeGlob undefine() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

    public void dynamicSaveState() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

    public void dynamicRestoreState() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

}
