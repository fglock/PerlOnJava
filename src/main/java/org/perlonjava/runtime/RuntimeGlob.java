package org.perlonjava.runtime;

import org.perlonjava.mro.InheritanceResolver;

import java.util.Iterator;

import static org.perlonjava.runtime.RuntimeScalarType.*;

/**
 * Represents a runtime typeglob in Perl. Typeglobs are special symbols that can represent
 * all types of Perl variables (scalars, arrays, hashes, subroutines, filehandles) with the same name.
 * This class provides methods to manipulate and interact with typeglobs in the runtime environment.
 */
public class RuntimeGlob extends RuntimeScalar implements RuntimeScalarReference {

    // The name of the typeglob
    public String globName;
    public RuntimeScalar IO;

    /**
     * Constructor for RuntimeGlob.
     * Initializes a new instance of the RuntimeGlob class with the specified glob name.
     *
     * @param globName The name of the typeglob.
     */
    public RuntimeGlob(String globName) {
        this.globName = globName;
        // Initialize the RuntimeScalar fields
        this.type = RuntimeScalarType.GLOB;
        this.value = this;
        this.IO = new RuntimeScalar();
    }

    public static boolean isGlobAssigned(String globName) {
        return GlobalVariable.globalGlobs.getOrDefault(globName, false);
    }

    /**
     * Sets the value of the typeglob based on the type of the provided RuntimeScalar.
     * Supports setting CODE and GLOB types, with special handling for IO objects.
     *
     * @param value The RuntimeScalar value to set.
     * @return The set RuntimeScalar value.
     * @throws IllegalStateException if the typeglob assignment is not implemented for the given type.
     */
    public RuntimeScalar set(RuntimeScalar value) {
        markGlobAsAssigned();

        // System.out.println("glob set " + this.globName + " to " + value.type);
        switch (value.type) {
            case CODE:
                GlobalVariable.getGlobalCodeRef(this.globName).set(value);

                // Invalidate the method resolution cache
                InheritanceResolver.invalidateCache();

                return value;
            case GLOB, GLOBREFERENCE:
                // *STDOUT = $new_handle
                if (value.value instanceof RuntimeGlob runtimeGlob) {
                    this.set(runtimeGlob);
                }
                return value;
            case ARRAYREFERENCE:
                // Handle the case where a typeglob is assigned a reference to an array
                if (value.value instanceof RuntimeArray) {
                    GlobalVariable.getGlobalArray(this.globName).setFromList(((RuntimeArray) value.value).getList());
                }
                return value;
            case HASHREFERENCE:
                if (value.value instanceof RuntimeHash) {
                    GlobalVariable.getGlobalHash(this.globName).setFromList(((RuntimeHash) value.value).getList());
                }
                return value;
            case REFERENCE:
                if (value.value instanceof RuntimeScalar) {
                    RuntimeScalar deref = value.scalarDeref();
                    // `*foo = \&bar` assigns to the CODE slot.
                    if (deref.type == RuntimeScalarType.CODE) {
                        GlobalVariable.getGlobalCodeRef(this.globName).set(deref);
                        InheritanceResolver.invalidateCache();
                    } else if (deref.type == RuntimeScalarType.ARRAYREFERENCE && deref.value instanceof RuntimeArray arr) {
                        // `*foo = \@bar` assigns to the ARRAY slot.
                        GlobalVariable.globalArrays.put(this.globName, arr);
                    } else if (deref.type == RuntimeScalarType.HASHREFERENCE && deref.value instanceof RuntimeHash hash) {
                        // `*foo = \%bar` assigns to the HASH slot.
                        GlobalVariable.globalHashes.put(this.globName, hash);
                    } else {
                        GlobalVariable.getGlobalVariable(this.globName).set(deref);
                    }
                }
                return value;
            case UNDEF:
                return value;
            case INTEGER:
            case DOUBLE:
            case STRING:
            case BYTE_STRING:
            case BOOLEAN:
            case VSTRING:
            case DUALVAR:
                // Handle scalar value assignments to typeglobs
                // This assigns the scalar value to the scalar slot of the typeglob
                GlobalVariable.getGlobalVariable(this.globName).set(value);
                return value;
            case FORMAT:
                // Handle format assignments to typeglobs
                // Share the same format reference instead of copying content
                if (value.value instanceof RuntimeFormat sourceFormat) {
                    GlobalVariable.setGlobalFormatRef(this.globName, sourceFormat);
                }
                return value;
        }
        throw new IllegalStateException("typeglob assignment not implemented for " + value.type);
    }

    /**
     * Sets the current RuntimeScalar object to the values associated with the given RuntimeGlob.
     * This method effectively implements the behavior of assigning one typeglob to another,
     * similar to Perl's typeglob assignment.
     * In Perl, *aaa = *bbb creates ALIASES, not copies. After this assignment:
     * - @aaa and @bbb are THE SAME array (share storage)
     * - %aaa and %bbb are THE SAME hash (share storage)
     * - $aaa and $bbb are THE SAME scalar (share storage)
     *
     * @param value The RuntimeGlob object whose associated values are to be assigned.
     * @return The scalar value associated with the provided RuntimeGlob.
     */
    public RuntimeScalar set(RuntimeGlob value) {
        markGlobAsAssigned();

        if (this.globName.endsWith("::") && value.globName.endsWith("::")) {
            GlobalVariable.setStashAlias(this.globName, value.globName);
            InheritanceResolver.invalidateCache();
            GlobalVariable.clearPackageCache();
            return value.scalar();
        }

        // Retrieve the RuntimeScalar value associated with the provided RuntimeGlob.
        RuntimeScalar result = value.scalar();

        // Retrieve the name of the glob from the provided RuntimeGlob object.
        String globName = value.globName;

        // Create ALIASES by making both names point to the same objects in the global maps
        // This is the key difference from the old implementation which created references
        
        // Alias the CODE slot: both names point to the same code reference
        RuntimeScalar sourceCode = GlobalVariable.getGlobalCodeRef(globName);
        GlobalVariable.globalCodeRefs.put(this.globName, (RuntimeScalar) sourceCode);
        // Invalidate the method resolution cache
        InheritanceResolver.invalidateCache();

        // Alias the IO slot: both names point to the same IO object
        RuntimeGlob sourceIO = GlobalVariable.getGlobalIO(globName);
        this.IO = sourceIO.IO;

        // Alias the ARRAY slot: both names point to the same RuntimeArray object
        RuntimeArray sourceArray = GlobalVariable.getGlobalArray(globName);
        GlobalVariable.globalArrays.put(this.globName, sourceArray);

        // Alias the HASH slot: both names point to the same RuntimeHash object
        RuntimeHash sourceHash = GlobalVariable.getGlobalHash(globName);
        GlobalVariable.globalHashes.put(this.globName, sourceHash);

        // Alias the SCALAR slot: both names point to the same RuntimeScalar object
        RuntimeScalar sourceScalar = GlobalVariable.getGlobalVariable(globName);
        GlobalVariable.globalVariables.put(this.globName, sourceScalar);

        // Alias the FORMAT slot: both names point to the same RuntimeFormat object
        RuntimeFormat sourceFormat = GlobalVariable.getGlobalFormatRef(globName);
        if (sourceFormat.isFormatDefined()) {
            GlobalVariable.setGlobalFormatRef(this.globName, sourceFormat);
        }

        // Return the scalar value associated with the provided RuntimeGlob.
        return value.scalar();
    }

    private void markGlobAsAssigned() {
        // Mark this name as having been assigned via glob syntax (e.g. *CORE::GLOBAL::do = ...)
        // This distinction is crucial because subroutines assigned via glob assignment
        // are eligible to override built-in operators, whereas those defined using
        // 'sub CORE::GLOBAL::do { ... }' are not, unless a glob was assigned first.
        //
        // PerlOnJava does not use real Perl globs (Gv structures), so this global
        // hash acts as a minimal tracking mechanism to emulate Perl's override behavior.
        //
        // Later, during compilation of built-in operators (like 'do EXPR'), we can consult
        // this map to determine whether to check for an override in CORE::GLOBAL.
        GlobalVariable.globalGlobs.put(globName, true);
    }

    /**
     * Retrieves a reference or value associated with a specific key from a global variable.
     *
     * <p>This method implements the dereferencing operation for a glob hash, allowing access
     * to various global entities such as CODE, IO, SCALAR, ARRAY, and HASH based on the
     * provided index. It returns a reference or value corresponding to the key.
     *
     * @param index The scalar representing the key to dereference.
     * @return A RuntimeScalar representing the dereferenced value or reference. If the key
     * is not recognized, an empty RuntimeScalar is returned.
     */
    @Override
    public RuntimeScalar hashDerefGet(RuntimeScalar index) {
        return getGlobSlot(index);
    }

    @Override
    public RuntimeScalar hashDerefGetNonStrict(RuntimeScalar index, String packageName) {
        // For typeglobs, slot access doesn't need symbolic reference resolution
        // Just access the slot directly
        return getGlobSlot(index);
    }

    /**
     * Get a typeglob slot (CODE, SCALAR, ARRAY, HASH, IO, FORMAT).
     * This is the common implementation for both strict and non-strict contexts.
     */
    private RuntimeScalar getGlobSlot(RuntimeScalar index) {
        // System.out.println("glob getGlobSlot " + index.toString());
        return switch (index.toString()) {
            case "CODE" -> {
                // Only return CODE ref if the subroutine is actually defined
                RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(this.globName);
                if (codeRef.type == RuntimeScalarType.CODE && codeRef.value instanceof RuntimeCode code) {
                    if (code.defined()) {
                        yield codeRef;
                    }
                }
                yield new RuntimeScalar(); // Return undef if code doesn't exist
            }
            case "PACKAGE" -> {
                // Return the package that owns this glob. If the package has been undefined,
                // its bless id will have been anonymized to "__ANON__".
                int lastColonIndex = this.globName.lastIndexOf("::");
                String pkg = lastColonIndex >= 0 ? this.globName.substring(0, lastColonIndex) : "main";
                yield new RuntimeScalar(NameNormalizer.getBlessStrForClassName(pkg));
            }
            case "IO" -> {
                // Accessing the IO slot yields a blessable reference-like value.
                // We model this by returning a GLOBREFERENCE wrapper around the RuntimeIO.
                if (IO != null && IO.type == RuntimeScalarType.GLOB && IO.value instanceof RuntimeIO) {
                    RuntimeScalar ioRef = new RuntimeScalar();
                    ioRef.type = RuntimeScalarType.GLOBREFERENCE;
                    ioRef.value = IO.value;
                    ioRef.blessId = IO.blessId;
                    yield ioRef;
                }
                yield IO;
            }
            case "SCALAR" -> GlobalVariable.getGlobalVariable(this.globName);
            case "ARRAY" -> {
                // Only return reference if array exists (has elements or was explicitly created)
                if (GlobalVariable.existsGlobalArray(this.globName)) {
                    yield GlobalVariable.getGlobalArray(this.globName).createReference();
                }
                yield new RuntimeScalar(); // Return undef if array doesn't exist
            }
            case "HASH" -> {
                // Only return reference if hash exists (has elements or was explicitly created)
                if (GlobalVariable.existsGlobalHash(this.globName)) {
                    yield GlobalVariable.getGlobalHash(this.globName).createReference();
                }
                yield new RuntimeScalar(); // Return undef if hash doesn't exist
            }
            case "FORMAT" -> GlobalVariable.getGlobalFormatRef(this.globName);
            default -> new RuntimeScalar();
        };
    }

    public RuntimeScalar getIO() {
        return this.IO;
    }

    public RuntimeGlob setIO(RuntimeScalar io) {
        this.IO = io;
        // If the IO scalar contains a RuntimeIO, set its glob name
        if (io.value instanceof RuntimeIO runtimeIO) {
            runtimeIO.globName = this.globName;
        }
        return this;
    }

    public RuntimeGlob setIO(RuntimeIO io) {
        // Set the glob name in the RuntimeIO for proper stringification
        io.globName = this.globName;
        this.IO = new RuntimeScalar(io);
        return this;
    }

    /**
     * Counts the number of elements in the typeglob.
     *
     * @return The number of elements, which is always 1 for a typeglob.
     */
    public int countElements() {
        return 1;
    }

    /**
     * Returns a string representation of the typeglob.
     *
     * @return A string in the format "*globName".
     */
    public String toString() {
        return "*" + this.globName;
    }

    /**
     * Returns a string representation of the typeglob reference.
     * The format is "GLOB(hashCode)" where hashCode is the unique identifier for this instance.
     *
     * @return A string representation of the typeglob reference.
     */
    public String toStringRef() {
        String ref = "GLOB(0x" + Integer.toHexString(this.hashCode()) + ")";
        return (blessId == 0
                ? ref
                : NameNormalizer.getBlessStr(blessId) + "=" + ref);
    }

    /**
     * Returns an integer representation of the typeglob reference.
     * This is the hash code of the current instance.
     *
     * @return The hash code of this instance.
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Returns a double representation of the typeglob reference.
     * This is the hash code of the current instance, cast to a double.
     *
     * @return The hash code of this instance as a double.
     */
    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Returns a boolean representation of the typeglob reference.
     * This always returns true, indicating the presence of the typeglob.
     *
     * @return Always true.
     */
    public boolean getBooleanRef() {
        return true;
    }

    /**
     * Returns a boolean indicating whether the typeglob is defined.
     *
     * @return Always true, as typeglobs are always considered defined.
     */
    public boolean getDefinedBoolean() {
        return true;
    }

    /**
     * Gets the scalar value of the typeglob.
     *
     * @return A RuntimeScalar representing the typeglob.
     */
    public RuntimeScalar scalar() {
        return this;
    }

    /**
     * Retrieves the boolean value of the typeglob.
     *
     * @return Always true, indicating the presence of the typeglob.
     */
    public boolean getBoolean() {
        return true;
    }

    /**
     * Creates a reference to the typeglob.
     *
     * @return A RuntimeScalar representing the reference to the typeglob.
     */
    public RuntimeScalar createReference() {
        RuntimeScalar ret = new RuntimeScalar();
        ret.type = RuntimeScalarType.GLOBREFERENCE;
        ret.value = this;
        return ret;
    }

    /**
     * Gets the list value of the typeglob.
     *
     * @return A RuntimeList containing the scalar representation of the typeglob.
     */
    public RuntimeList getList() {
        return new RuntimeList(this.scalar());
    }

    /**
     * Adds itself to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar to which this typeglob will be added.
     * @return The updated RuntimeScalar.
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this);
    }

    /**
     * Sets itself from a RuntimeList.
     *
     * @param value The RuntimeList from which this typeglob will be set.
     * @return The updated RuntimeArray.
     */
    public RuntimeArray setFromList(RuntimeList value) {
        return new RuntimeArray(this.set(value.scalar()));
    }

    /**
     * The keys() operator for typeglobs.
     *
     * @return Throws an IllegalStateException as typeglobs do not support keys.
     */
    public RuntimeArray keys() {
        throw new PerlCompilerException("Type of arg 1 to keys must be hash or array");
    }

    /**
     * The values() operator for typeglobs.
     *
     * @return Throws an IllegalStateException as typeglobs do not support values.
     */
    public RuntimeArray values() {
        throw new PerlCompilerException("Type of arg 1 to values must be hash or array");
    }

    /**
     * The each() operator for typeglobs.
     *
     * @return Throws an IllegalStateException as typeglobs do not support each.
     */
    public RuntimeList each() {
        throw new PerlCompilerException("Type of arg 1 to each must be hash or array");
    }

    /**
     * Performs the chop operation on the typeglob.
     *
     * @return Throws an IllegalStateException as chop is not implemented for typeglobs.
     */
    public RuntimeScalar chop() {
        throw new PerlCompilerException("chop glob is not implemented");
    }

    /**
     * Performs the chomp operation on the typeglob.
     *
     * @return Throws an IllegalStateException as chomp is not implemented for typeglobs.
     */
    public RuntimeScalar chomp() {
        throw new PerlCompilerException("chomp glob is not implemented");
    }

    /**
     * Returns an iterator over the elements of type RuntimeScalar.
     *
     * @return An Iterator<RuntimeScalar> for iterating over the elements.
     */
    public Iterator<RuntimeScalar> iterator() {
        return super.iterator();
    }

    /**
     * Gets the Glob alias into an Array.
     *
     * @param arr The RuntimeArray to which the alias will be added.
     * @return The updated RuntimeArray.
     */
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        arr.elements.add(this.scalar());
        return arr;
    }

    /**
     * Undefines the elements of the typeglob.
     * This method clears the CODE reference and invalidates the method resolution cache.
     *
     * @return The current RuntimeGlob instance after undefining its elements.
     */
    public RuntimeGlob undefine() {
        if (this.globName.endsWith("::")) {
            new RuntimeStash(this.globName).undefine();
            return this;
        }
        // Undefine CODE
        GlobalVariable.getGlobalCodeRef(this.globName).set(new RuntimeScalar());

        // Invalidate the method resolution cache
        InheritanceResolver.invalidateCache();

        // Undefine FORMAT
        GlobalVariable.getGlobalFormatRef(this.globName).undefineFormat();

        // XXX TODO undefine scalar, array, hash
        return this;
    }

    /**
     * Saves the current state of the typeglob.
     */
    @Override
    public void dynamicSaveState() {
        GlobalVariable.getGlobalCodeRef(this.globName).dynamicSaveState();
        GlobalVariable.getGlobalArray(this.globName).dynamicSaveState();
        GlobalVariable.getGlobalHash(this.globName).dynamicSaveState();
        GlobalVariable.getGlobalVariable(this.globName).dynamicSaveState();
        GlobalVariable.getGlobalFormatRef(this.globName).dynamicSaveState();
        this.IO.dynamicSaveState();
    }

    /**
     * Restores the most recently saved state of the typeglob.
     */
    @Override
    public void dynamicRestoreState() {
        this.IO.dynamicRestoreState();
        GlobalVariable.getGlobalVariable(this.globName).dynamicRestoreState();
        GlobalVariable.getGlobalHash(this.globName).dynamicRestoreState();
        GlobalVariable.getGlobalArray(this.globName).dynamicRestoreState();
        GlobalVariable.getGlobalCodeRef(this.globName).dynamicRestoreState();
        GlobalVariable.getGlobalFormatRef(this.globName).dynamicRestoreState();
    }
}
