package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.perlonjava.runtime.ErrorMessageUtil;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.ScopedSymbolTable;

import java.util.EnumMap;
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
    private final Map<RuntimeContextType, EmitterContext> contextCache = new EnumMap<>(RuntimeContextType.class);
    /**
     * Debugging options.
     */
    public boolean debugEnabled;  //  Flag to enable or disable debugging
    public boolean tokenizeOnly;
    public boolean compileOnly;
    public boolean parseOnly;
    /**
     * The name of the file being processed.
     */
    public String fileName;
    /**
     * The name of the Java class being generated.
     */
    public String javaClassName;
    /**
     * The symbol table used for scoping symbols within the context.
     */
    public ScopedSymbolTable symbolTable;
    /**
     * The label to which the method should return.
     */
    public Label returnLabel;
    /**
     * The MethodVisitor instance used to visit the method instructions.
     */
    public MethodVisitor mv;
    /**
     * The type of the current context, defined by the RuntimeContextType enum - VOID, SCALAR, etc
     */
    public RuntimeContextType contextType;
    /**
     * Indicates whether the current context is for a boxed object (true) or a native object (false).
     */
    public boolean isBoxed;
    /**
     * Formats error messages with source code context
     */
    public ErrorMessageUtil errorUtil;

    /**
     * Constructs a new EmitterContext with the specified parameters.
     *
     * @param fileName      the name of the file being processed
     * @param javaClassName the name of the Java class being generated
     * @param symbolTable   the symbol table used for scoping symbols within the context
     * @param returnLabel   the label to which the method should return
     * @param mv            the MethodVisitor instance used to visit the method instructions
     * @param contextType   the type of the context, defined by the RuntimeContextType enum
     * @param isBoxed       indicates whether the context is for a boxed object (true) or a native object (false)
     * @param errorUtil     formats error messages with source code context
     * @param debugEnabled  enables or disables printing debug messages with ctx.logDebug(message)
     * @param tokenizeOnly  stop after the tokenizing step
     * @param compileOnly   stop after the compilation step
     */
    public EmitterContext(
            String fileName,
            String javaClassName,
            ScopedSymbolTable symbolTable,
            Label returnLabel,
            MethodVisitor mv,
            RuntimeContextType contextType,
            boolean isBoxed,
            ErrorMessageUtil errorUtil,
            boolean debugEnabled,
            boolean tokenizeOnly,
            boolean compileOnly,
            boolean parseOnly) {
        this.fileName = fileName;
        this.javaClassName = javaClassName;
        this.symbolTable = symbolTable;
        this.returnLabel = returnLabel;
        this.mv = mv;
        this.contextType = contextType;
        this.isBoxed = isBoxed;
        this.errorUtil = errorUtil;
        this.debugEnabled = debugEnabled;
        this.tokenizeOnly = tokenizeOnly;
        this.compileOnly = compileOnly;
        this.parseOnly = parseOnly;
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
    public EmitterContext with(RuntimeContextType contextType) {
        // Check if the context is already cached
        if (contextCache.containsKey(contextType)) {
            return contextCache.get(contextType);
        }
        // Create a new context and cache it
        EmitterContext newContext = new EmitterContext(this.fileName, this.javaClassName, this.symbolTable, this.returnLabel, this.mv, contextType, this.isBoxed, errorUtil, debugEnabled, tokenizeOnly, compileOnly, parseOnly);
        contextCache.put(contextType, newContext);
        return newContext;
    }

    public void logDebug(String message) {
        if (this.debugEnabled) { // Use ctx.debugEnabled
            System.out.println(message);
        }
    }
}

