package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.frontend.parser.SpecialBlockParser;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.regex.RuntimeRegex;

import java.util.Stack;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.PROXY;

/**
 * Represents a Perl special scalar variable, such as $`, $&, $', or $1.
 * These variables are used to capture specific parts of a string during regex operations.
 * The class extends RuntimeBaseProxy to provide access to these special variables.
 *
 * <p>This class provides functionality to handle special scalar variables in Perl,
 * which are typically used in the context of regular expression operations. Each
 * special variable has a specific role, such as capturing matched substrings or
 * parts of the string before or after a match.</p>
 */
public class ScalarSpecialVariable extends RuntimeBaseProxy {

    private static final Stack<InputLineState> inputLineStateStack = new Stack<>();
    // The type of special variable, represented by an enum.
    final Id variableId;
    // The position of the capture group, used only for CAPTURE type variables.
    final int position;

    /**
     * Constructs a ScalarSpecialVariable for a specific type of special variable.
     *
     * @param variableId The type of special variable (e.g., PREMATCH, MATCH, POSTMATCH).
     */
    public ScalarSpecialVariable(Id variableId) {
        super();
        this.type = PROXY;
        this.variableId = variableId;
        this.position = 0; // Default position is 0 for non-capture variables.
    }

    /**
     * Constructs a ScalarSpecialVariable for a specific capture group position.
     *
     * @param variableId The type of special variable (e.g., CAPTURE).
     * @param position   The position of the capture group.
     */
    public ScalarSpecialVariable(Id variableId, int position) {
        super();
        this.type = PROXY;
        this.variableId = variableId;
        this.position = position;
    }

    /**
     * Throws an exception as this variable represents a constant item
     * and cannot be modified.
     *
     * <p>This method is overridden to prevent modification of the special
     * variable, as these are intended to be read-only.</p>
     */
    @Override
    void vivify() {
        if (variableId == Id.INPUT_LINE_NUMBER) {
            if (lvalue == null) {
                lvalue = new RuntimeScalar(0);
            }
            return;
        }
        // HINTS doesn't need lvalue - it always reads/writes from the symbol table
        if (variableId == Id.HINTS) {
            return;
        }
        // WARNING_BITS doesn't need lvalue - it always reads/writes from the symbol table
        if (variableId == Id.WARNING_BITS) {
            return;
        }
        throw new PerlCompilerException("Modification of a read-only value attempted");
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        if (variableId == Id.INPUT_LINE_NUMBER) {
            vivify();
            if (RuntimeIO.getLastAccessedHandle() != null) {
                RuntimeIO.getLastAccessedHandle().currentLineNumber = value.getInt();
                lvalue.set(RuntimeIO.getLastAccessedHandle().currentLineNumber);
            } else {
                lvalue.set(value);
            }
            this.type = lvalue.type;
            this.value = lvalue.value;
            return lvalue;
        }
        if (variableId == Id.HINTS) {
            int hints = value.getInt();
            // Update the symbol table's strict options directly
            // No need to store in lvalue since reading always uses the symbol table
            ScopedSymbolTable symbolTable = SpecialBlockParser.getCurrentScope();
            if (symbolTable != null) {
                symbolTable.setStrictOptions(hints);
            }
            // Return a scalar with the hints value
            return getScalarInt(hints);
        }
        if (variableId == Id.WARNING_BITS) {
            // ${^WARNING_BITS} - Set warning bits from a string
            // This is used by Test::Builder to restore warning state in eval blocks
            ScopedSymbolTable symbolTable = SpecialBlockParser.getCurrentScope();
            if (symbolTable != null) {
                String bits = value.toString();
                WarningFlags.setWarningBitsFromString(symbolTable, bits);
            }
            return value;
        }
        return super.set(value);
    }

    // Add itself to a RuntimeArray.
    public void addToArray(RuntimeArray array) {
        array.elements.add(new RuntimeScalar(this.getValueAsScalar()));
    }

    /**
     * Adds the string value of this special variable to another scalar variable.
     *
     * @param var The scalar variable to which the value will be added.
     * @return The updated scalar variable.
     */
    public RuntimeScalar addToScalar(RuntimeScalar var) {
        return this.getValueAsScalar().addToScalar(var);
    }

    /**
     * Retrieves the RuntimeScalar value of the special variable based on its type.
     *
     * @return The RuntimeScalar value of the special variable, or null if not available.
     */
    public RuntimeScalar getValueAsScalar() {
        try {
            RuntimeScalar result = switch (variableId) {
                case CAPTURE -> {
                    String capture = RuntimeRegex.captureString(position);
                    yield capture != null ? makeRegexResultScalar(capture) : scalarUndef;
                }
                case MATCH -> {
                    String match = RuntimeRegex.matchString();
                    yield match != null ? makeRegexResultScalar(match) : scalarUndef;
                }
                case PREMATCH -> {
                    String prematch = RuntimeRegex.preMatchString();
                    yield prematch != null ? makeRegexResultScalar(prematch) : scalarUndef;
                }
                case POSTMATCH -> {
                    String postmatch = RuntimeRegex.postMatchString();
                    yield postmatch != null ? makeRegexResultScalar(postmatch) : scalarUndef;
                }
                case P_PREMATCH -> {
                    if (!RuntimeRegex.getLastMatchUsedPFlag()) yield scalarUndef;
                    String prematch = RuntimeRegex.preMatchString();
                    yield prematch != null ? makeRegexResultScalar(prematch) : scalarUndef;
                }
                case P_MATCH -> {
                    if (!RuntimeRegex.getLastMatchUsedPFlag()) yield scalarUndef;
                    String match = RuntimeRegex.matchString();
                    yield match != null ? makeRegexResultScalar(match) : scalarUndef;
                }
                case P_POSTMATCH -> {
                    if (!RuntimeRegex.getLastMatchUsedPFlag()) yield scalarUndef;
                    String postmatch = RuntimeRegex.postMatchString();
                    yield postmatch != null ? makeRegexResultScalar(postmatch) : scalarUndef;
                }
                case LAST_FH -> {
                    if (RuntimeIO.getLastAccessedHandle() == null) {
                        yield scalarUndef;
                    }
                    String globName = RuntimeIO.getLastAccessedHandle().globName;
                    if (globName != null) {
                        // Extract package and name from the glob name
                        String packageName;
                        String name;
                        int lastColon = globName.lastIndexOf("::");
                        if (lastColon > 0) {
                            packageName = globName.substring(0, lastColon);
                            name = globName.substring(lastColon + 2);
                        } else {
                            packageName = "main";
                            name = globName;
                        }

                        // Get the stash and access the glob
                        // The stash key must end with "::" for package stashes
                        RuntimeHash stash = HashSpecialVariable.getStash(packageName + "::");
                        RuntimeScalar glob = stash.get(name);
                        if (glob.type == RuntimeScalarType.GLOB) {
                            // ${^LAST_FH} returns a GLOB reference (like \*FH)
                            // This allows *{${^LAST_FH}} to work under strict refs
                            RuntimeGlob runtimeGlob = (RuntimeGlob) glob.value;
                            yield runtimeGlob.createReference();
                        }
                    }
                    // Fallback to the RuntimeIO object if no glob name is available
                    yield new RuntimeScalar(RuntimeIO.getLastAccessedHandle());
                }
                case INPUT_LINE_NUMBER -> {
                    if (RuntimeIO.getLastAccessedHandle() == null) {
                        if (lvalue != null) {
                            yield lvalue;
                        }
                        yield scalarUndef;
                    }
                    yield getScalarInt(RuntimeIO.getLastAccessedHandle().currentLineNumber);
                }
                case LAST_PAREN_MATCH -> {
                    String lastCapture = RuntimeRegex.lastCaptureString();
                    yield lastCapture != null ? new RuntimeScalar(lastCapture) : scalarUndef;
                }
                case LAST_SUCCESSFUL_PATTERN -> RuntimeRegex.getLastSuccessfulPattern() != null
                        ? new RuntimeScalar(RuntimeRegex.getLastSuccessfulPattern()) : scalarUndef;
                case LAST_REGEXP_CODE_RESULT -> {
                    // $^R - Result of last (?{...}) code block
                    // Get the last matched regex and retrieve its code block result
                    if (RuntimeRegex.getLastSuccessfulPattern() != null) {
                        RuntimeScalar codeBlockResult = RuntimeRegex.getLastSuccessfulPattern().getLastCodeBlockResult();
                        yield codeBlockResult != null ? codeBlockResult : scalarUndef;
                    }
                    yield scalarUndef;
                }
                case HINTS -> {
                    // $^H - Always read from the current scope's symbol table
                    // This ensures lexical scoping - each scope has its own $^H value
                    ScopedSymbolTable symbolTable = SpecialBlockParser.getCurrentScope();
                    if (symbolTable != null) {
                        yield getScalarInt(symbolTable.getStrictOptions());
                    }
                    yield getScalarInt(0);
                }
                case REAL_GID -> {
                    // $( - Real group ID (lazy evaluation to avoid JNA overhead at startup)
                    yield new RuntimeScalar(NativeUtils.getgid(0));
                }
                case EFFECTIVE_GID -> {
                    // $) - Effective group ID (lazy evaluation to avoid JNA overhead at startup)
                    yield new RuntimeScalar(NativeUtils.getegid(0));
                }
                case REAL_UID -> {
                    // $< - Real user ID (lazy evaluation to avoid JNA overhead at startup)
                    yield NativeUtils.getuid(0);
                }
                case EFFECTIVE_UID -> {
                    // $> - Effective user ID (lazy evaluation to avoid JNA overhead at startup)
                    yield NativeUtils.geteuid(0);
                }
                case WARNING_BITS -> {
                    // ${^WARNING_BITS} - Compile-time warning bits
                    // Always read from the current scope's symbol table
                    ScopedSymbolTable symbolTable = SpecialBlockParser.getCurrentScope();
                    if (symbolTable != null) {
                        String bits = symbolTable.getWarningBitsString();
                        yield new RuntimeScalar(bits);
                    }
                    yield scalarUndef;
                }
                case EVAL_STATE -> {
                    // $^S - Current state of the interpreter
                    // undef = parsing/compiling (BEGIN blocks)
                    // 1 = inside eval (eval STRING or eval BLOCK)
                    // 0 = otherwise (normal execution)
                    String globalPhase = GlobalVariable.getGlobalVariable(GlobalContext.GLOBAL_PHASE).toString();
                    if ("START".equals(globalPhase)) {
                        // During BEGIN/UNITCHECK blocks = compilation phase
                        yield scalarUndef;
                    }
                    yield getScalarInt(RuntimeCode.evalDepth > 0 ? 1 : 0);
                }
            };
            return result;
        } catch (IllegalStateException e) {
            return scalarUndef;
        }
    }

    public RuntimeScalar getNumber() {
        return this.getValueAsScalar().getNumber();
    }

    @Override
    public RuntimeScalar getNumber(String operation) {
        return this.getValueAsScalar().getNumber(operation);
    }

    @Override
    public boolean isString() {
        return this.getValueAsScalar().isString();
    }

    /**
     * Converts the special variable to a number with uninitialized warnings.
     *
     * @param operation The operation name for the warning message.
     * @return The numeric value of the special variable.
     */
    @Override
    public RuntimeScalar getNumberWarn(String operation) {
        return this.getValueAsScalar().getNumberWarn(operation);
    }

    /**
     * Retrieves the integer representation of the special variable.
     *
     * @return The integer value of the special variable.
     */
    @Override
    public int getInt() {
        return this.getValueAsScalar().getInt();
    }

    /**
     * Retrieves the long representation of the special variable.
     *
     * @return The long value of the special variable.
     */
    @Override
    public long getLong() {
        return this.getValueAsScalar().getLong();
    }

    /**
     * Retrieves the double representation of the special variable.
     *
     * @return The double value of the special variable.
     */
    @Override
    public double getDouble() {
        return this.getValueAsScalar().getDouble();
    }

    /**
     * Returns the string representation of the special variable.
     *
     * @return The string value of the special variable.
     */
    @Override
    public String toString() {
        return this.getValueAsScalar().toString();
    }

    /**
     * Evaluates the boolean representation of the special variable.
     *
     * @return True value of the special variable.
     */
    @Override
    public boolean getBoolean() {
        return this.getValueAsScalar().getBoolean();
    }

    /**
     * Checks if the special variable is defined.
     *
     * @return True if the value is not null.
     */
    @Override
    public boolean getDefinedBoolean() {
        return this.getValueAsScalar().getDefinedBoolean();
    }

    /**
     * Get the special variable as a file handle.
     *
     * @return The file handle associated with the special variable.
     */
    @Override
    public RuntimeIO getRuntimeIO() {
        return this.getValueAsScalar().getRuntimeIO();
    }

    /**
     * Dereference as a glob (strict refs version).
     * This delegates to the computed value's globDeref().
     *
     * @return The RuntimeGlob from the computed value.
     */
    @Override
    public RuntimeGlob globDeref() {
        return this.getValueAsScalar().globDeref();
    }

    /**
     * Dereference as a glob (non-strict refs version).
     * This delegates to the computed value's globDerefNonStrict().
     *
     * @param packageName The package name for symbolic reference resolution.
     * @return The RuntimeGlob from the computed value.
     */
    @Override
    public RuntimeGlob globDerefNonStrict(String packageName) {
        return this.getValueAsScalar().globDerefNonStrict(packageName);
    }

    /**
     * Adds this entity to the specified RuntimeList.
     *
     * @param list the RuntimeList to which this entity will be added
     */
    @Override
    public void addToList(RuntimeList list) {
        list.add(this.getValueAsScalar());
    }

    @Override
    public RuntimeList getList() {
        return new RuntimeList(this.getValueAsScalar());
    }

    /**
     * Saves the current state of the RuntimeScalar instance.
     *
     * <p>This method creates a snapshot of the current type and value of the scalar,
     * and pushes it onto a static stack for later restoration.
     */
    @Override
    public void dynamicSaveState() {
        if (variableId == Id.INPUT_LINE_NUMBER) {
            RuntimeIO handle = RuntimeIO.getLastAccessedHandle();
            int lineNumber = handle != null ? handle.currentLineNumber : (lvalue != null ? lvalue.getInt() : 0);
            RuntimeScalar localValue = lvalue != null ? new RuntimeScalar(lvalue) : null;
            inputLineStateStack.push(new InputLineState(handle, lineNumber, localValue));
            return;
        }
        super.dynamicSaveState();
    }

    /**
     * Restores the most recently saved state of the RuntimeScalar instance.
     *
     * <p>This method pops the most recent state from the static stack and restores
     * the type and value to the current scalar. If no state is saved, it does nothing.
     */
    @Override
    public void dynamicRestoreState() {
        if (variableId == Id.INPUT_LINE_NUMBER) {
            if (!inputLineStateStack.isEmpty()) {
                InputLineState previous = inputLineStateStack.pop();
                RuntimeIO.setLastAccessedHandle(previous.lastHandle);
                if (previous.lastHandle != null) {
                    previous.lastHandle.currentLineNumber = previous.lastLineNumber;
                }
                lvalue = previous.localValue;
                if (lvalue != null) {
                    this.type = lvalue.type;
                    this.value = lvalue.value;
                }
            }
            return;
        }
        super.dynamicRestoreState();
    }

    /**
     * Creates a RuntimeScalar from a regex match result string, preserving
     * BYTE_STRING type if the matched input was a byte string.
     */
    private static RuntimeScalar makeRegexResultScalar(String value) {
        RuntimeScalar scalar = new RuntimeScalar(value);
        if (RuntimeRegex.getLastMatchWasByteString()) {
            scalar.type = RuntimeScalarType.BYTE_STRING;
        }
        return scalar;
    }

    /**
     * Enum to represent the id of the special variable.
     *
     * <p>This enum defines the different types of special variables that can be
     * represented by this class, each corresponding to a specific role in regex
     * operations or file handling.</p>
     */
    public enum Id {
        CAPTURE,   // Represents a captured substring.
        PREMATCH,  // Represents the part of the string before the matched substring.
        MATCH,     // Represents the matched substring.
        POSTMATCH, // Represents the part of the string after the matched substring.
        P_PREMATCH,  // ${^PREMATCH} - only defined when last match used /p
        P_MATCH,     // ${^MATCH} - only defined when last match used /p
        P_POSTMATCH, // ${^POSTMATCH} - only defined when last match used /p
        LAST_FH,    // Represents the last filehandle used in an input operation.
        INPUT_LINE_NUMBER, // Represents the current line number in an input operation.
        LAST_PAREN_MATCH, // The highest capture variable ($1, $2, ...) which has a defined value.
        LAST_SUCCESSFUL_PATTERN, // ${^LAST_SUCCESSFUL_PATTERN}
        LAST_REGEXP_CODE_RESULT, // $^R - Result of last (?{...}) code block in regex
        HINTS, // $^H - Compile-time hints (strict, etc.)
        REAL_GID, // $( - Real group ID (lazy, JNA call only on access)
        EFFECTIVE_GID, // $) - Effective group ID (lazy, JNA call only on access)
        REAL_UID, // $< - Real user ID (lazy, JNA call only on access)
        EFFECTIVE_UID, // $> - Effective user ID (lazy, JNA call only on access)
        WARNING_BITS, // ${^WARNING_BITS} - Compile-time warning bits
        EVAL_STATE, // $^S - Current state of the interpreter (undef=compiling, 0=not in eval, 1=in eval)
    }

    private record InputLineState(RuntimeIO lastHandle, int lastLineNumber, RuntimeScalar localValue) {
    }
}
