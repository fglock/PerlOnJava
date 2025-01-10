package org.perlonjava.codegen;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.perlonjava.ArgumentParser;
import org.perlonjava.runtime.ErrorMessageUtil;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.symbols.ScopedSymbolTable;

import java.util.HashMap;
import java.util.Map;

/**
 * The EmitterContext class holds the context information required for parsing and emitting bytecode.
 * This includes details about the file, class, symbol table, method visitor, context type,
 * and context for error messages and debug messages.
 */
public class EmitterContext {

    /**
     * Cache for contexts with different ContextTypes
     */
    private final Map<Integer, EmitterContext> contextCache = new HashMap<>();

    /**
     * CompilerOptions is a configuration class that holds various settings and flags
     * used by the compiler during the compilation process.
     */
    public ArgumentParser.CompilerOptions compilerOptions;

    /**
     * The information on the Java class being generated.
     */
    public JavaClassInfo javaClassInfo;
    /**
     * The symbol table used for scoping symbols within the context.
     */
    public ScopedSymbolTable symbolTable;

    /**
     * The ClassWriter instance used to visit the method instructions.
     */
    public ClassWriter cw;
    /**
     * The MethodVisitor instance used to visit the method instructions.
     */
    public MethodVisitor mv;
    /**
     * The type of the current context, defined by the RuntimeContextType enum - VOID, SCALAR, etc
     */
    public int contextType;
    /**
     * Indicates whether the current context is for a boxed object (true) or a native object (false).
     */
    public boolean isBoxed;
    /**
     * Formats error messages with source code context
     */
    public ErrorMessageUtil errorUtil;

    /**
     * List of UNITCHECK blocks to execute after compilation phase
     */
    public RuntimeArray unitcheckBlocks;

    /**
     * Active after \Q in double quoted string
     */
    public boolean quoteMetaEnabled;

    /**
     * Constructs a new EmitterContext with the specified parameters.
     *
     * @param javaClassInfo   the name of the Java class being generated
     * @param symbolTable     the symbol table used for scoping symbols within the context
     * @param mv              the MethodVisitor instance used to visit the method instructions
     * @param cw              the ClassWriter instance used to visit the method instructions
     * @param contextType     the type of the context, defined by the RuntimeContextType int value
     * @param isBoxed         indicates whether the context is for a boxed object (true) or a native object (false)
     * @param errorUtil       formats error messages with source code context
     * @param compilerOptions compiler flags, file name and source code
     * @param unitcheckBlocks list of UNITCHECK blocks to execute after compilation phase
     */
    public EmitterContext(
            JavaClassInfo javaClassInfo,
            ScopedSymbolTable symbolTable,
            MethodVisitor mv,
            ClassWriter cw,
            int contextType,
            boolean isBoxed,
            ErrorMessageUtil errorUtil,
            ArgumentParser.CompilerOptions compilerOptions,
            RuntimeArray unitcheckBlocks) {
        this.javaClassInfo = javaClassInfo;
        this.symbolTable = symbolTable;
        this.mv = mv;
        this.cw = cw;
        this.contextType = contextType;
        this.isBoxed = isBoxed;
        this.errorUtil = errorUtil;
        this.compilerOptions = compilerOptions;
        this.unitcheckBlocks = unitcheckBlocks;
    }

    /**
     * Creates a new EmitterContext with the specified context type.
     * The other properties are copied from the current context.
     * <p>
     * This is used for example when the context changes from VOID to SCALAR
     *
     * @param contextType the new context type
     * @return a new EmitterContext with the updated context type
     */
    public EmitterContext with(int contextType) {
        // Check if the context is already cached
        if (contextCache.containsKey(contextType)) {
            return contextCache.get(contextType);
        }
        // Create a new context and cache it
        EmitterContext newContext = new EmitterContext(
                this.javaClassInfo, this.symbolTable,
                this.mv, this.cw, contextType, this.isBoxed, this.errorUtil, this.compilerOptions,
                this.unitcheckBlocks);
        contextCache.put(contextType, newContext);
        return newContext;
    }

    public void logDebug(String message) {
        if (this.compilerOptions.debugEnabled) { // Use ctx.debugEnabled
            System.out.println(message);
        }
    }

    @Override
    public String toString() {
        return "EmitterContext{\n" +
                "    JavaClassInfo='" + javaClassInfo + "',\n" +
                "    symbolTable=" + symbolTable + ",\n" +
                "    contextType=" + contextType + ",\n" +
                "    isBoxed=" + isBoxed + ",\n" +
                "    errorUtil=" + (errorUtil != null ? errorUtil.toString() : "null") + ",\n" +
                "    compilerOptions=" + (compilerOptions != null ? compilerOptions.toString() : "null") + "\n" +
                "}";
    }
}

