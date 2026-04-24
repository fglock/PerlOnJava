package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.operators.WarnDie;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * Represents a stash entry in Perl.
 */
public class RuntimeStashEntry extends RuntimeGlob {

    /**
     * Constructor for RuntimeStashEntry.
     * Initializes a new instance of the RuntimeStashEntry class with the specified glob name.
     *
     * @param globName The name of the typeglob.
     */
    public RuntimeStashEntry(String globName, boolean isDefined) {
        super(globName);
        if (!isDefined) {
            type = RuntimeScalarType.UNDEF;
        }
        // System.out.println("Stash Entry create: " + globName + " " + isDefined);
    }

    /**
     * Stash entries should not be detached - they need to preserve their type
     * for ref() to return empty string correctly.
     * 
     * @return this same instance
     */
    @Override
    public RuntimeGlob createDetachedCopy() {
        return this;
    }

    /**
     * Override globDeref for stash entries to return a plain RuntimeGlob.
     * This ensures that *{$stash{name}} = \$value goes through RuntimeGlob.set()
     * (which only aliases slots) rather than RuntimeStashEntry.set() (which creates
     * constant subs). Direct stash assignment $stash{name} = \$value still calls
     * RuntimeStashEntry.set() and creates constant subs as expected.
     */
    @Override
    public RuntimeGlob globDeref() {
        RuntimeGlob pure = new RuntimeGlob(this.globName);
        pure.IO = this.IO;
        return pure;
    }

    @Override
    public RuntimeGlob globDerefNonStrict(String packageName) {
        RuntimeGlob pure = new RuntimeGlob(this.globName);
        pure.IO = this.IO;
        return pure;
    }

// Note on Stash Operations:
//
// In Perl, a typeglob is a structure that holds a symbol table entry and a key (or slot).
// An example of using a typeglob is:
//   $constant::{_CAN_PCS} = \$const;
// This line effectively binds a constant at compile time, allowing it to be accessed without a sigil.
//
// When Perl encounters a bareword (such as _CAN_PCS), it resolves it by:
// 1. Checking if it matches a subroutine name.
// 2. Looking up the symbol table for a corresponding typeglob entry.
// 3. If a reference is found in the symbol table, Perl uses that value.
//
// Additionally, if you place an array reference in a symbol table slot where Perl expects a subroutine reference
// (e.g., using \@list where a \&sub would normally be), Perl automatically creates a subroutine that returns
// the array value when called. This behavior also applies to scalar values.

    /**
     * Sets the value of the typeglob based on the type of the provided RuntimeScalar.
     * Supports setting CODE and GLOB types, with special handling for IO objects.
     *
     * @param value The RuntimeScalar value to set.
     * @return The set RuntimeScalar value.
     * @throws IllegalStateException if the typeglob assignment is not implemented for the given type.
     */
    public RuntimeScalar set(RuntimeScalar value) {
        type = RuntimeScalarType.GLOB;
        if (value.type == READONLY_SCALAR) {
            return set((RuntimeScalar) value.value);
        }
        if (value.type == REFERENCE) {
            if (value.value instanceof RuntimeScalar) {
                RuntimeScalar deref = value.scalarDeref();
                if (deref.type == CODE) {
                    // `$stash->{foo} = \&bar` creates a constant subroutine returning the code reference
                    RuntimeCode code = new RuntimeCode("", null);
                    code.constantValue = deref.getList();
                    GlobalVariable.defineGlobalCodeRef(this.globName).set(
                            new RuntimeScalar(code));
                    InheritanceResolver.invalidateCache();
                } else if (deref.type == HASHREFERENCE) {
                    // `$stash->{foo} = \$hash_ref` creates a constant subroutine returning the hash reference
                    RuntimeCode code = new RuntimeCode("", null);
                    code.constantValue = deref.getList();
                    GlobalVariable.defineGlobalCodeRef(this.globName).set(
                            new RuntimeScalar(code));
                } else if (deref.type == ARRAYREFERENCE) {
                    // `$stash->{foo} = \$array_ref` creates a constant subroutine returning the array reference
                    RuntimeCode code = new RuntimeCode("", null);
                    code.constantValue = deref.getList();
                    GlobalVariable.defineGlobalCodeRef(this.globName).set(
                            new RuntimeScalar(code));
                } else if (deref.type == GLOB) {
                    // `$stash->{foo} = \*bar` creates a constant subroutine returning the glob
                    RuntimeCode code = new RuntimeCode("", null);
                    code.constantValue = new RuntimeList(deref);
                    GlobalVariable.defineGlobalCodeRef(this.globName).set(
                            new RuntimeScalar(code));
                } else {
                    // Default: constant subroutine for bareword access.
                    // Do NOT set the scalar slot here.  In Perl 5, stash
                    // assignment of a scalar ref ($stash{name} = \$scalar)
                    // only creates a constant sub (&name); it never touches
                    // the scalar variable ($name).  Setting the scalar slot
                    // causes "Modification of a read-only value attempted"
                    // when both `use constant FOO => ...` and `our $FOO`
                    // exist in the same package (e.g. Template::Parser).
                    RuntimeCode code = new RuntimeCode("", null);
                    code.constantValue = deref.getList();
                    GlobalVariable.defineGlobalCodeRef(this.globName).set(
                            new RuntimeScalar(code));
                }
            }
            return value;
        }
        if (value.type == ARRAYREFERENCE) {
            if (value.value instanceof RuntimeArray) {
                RuntimeArray targetArray = value.arrayDeref();
                // Make the target array slot point to the same RuntimeArray object (aliasing)
                GlobalVariable.globalArrays.put(this.globName, targetArray);

                // Also create a constant subroutine for bareword access
                RuntimeCode code = new RuntimeCode("", null);
                code.constantValue = targetArray.getList();
                GlobalVariable.defineGlobalCodeRef(this.globName).set(
                        new RuntimeScalar(code));
            }
            return value;
        }


        switch (value.type) {
            case TIED_SCALAR:
                return set(value.tiedFetch());
            case CODE:
                GlobalVariable.defineGlobalCodeRef(this.globName).set(value);

                // Invalidate the method resolution cache
                InheritanceResolver.invalidateCache();

                return value;
            case FORMAT:
                // Handle format assignments to typeglobs
                GlobalVariable.getGlobalFormatRef(this.globName).set(value);
                return value;
            case GLOB:
                if (value.value instanceof RuntimeIO) {
                    // *STDOUT = $new_handle
                    GlobalVariable.getGlobalIO(this.globName).set(value);
                } else if (value.value instanceof RuntimeGlob sourceGlob) {
                    // *dest = *source - copy all slots from source glob to dest glob
                    String sourceGlobName = sourceGlob.globName;

                    // Mark the destination glob as assigned so that parser checks
                    // like RuntimeGlob.isGlobAssigned() see it as installed.
                    // This matters for CORE::GLOBAL::require round-trips via
                    // delete+reassign of stash entries, where the destination
                    // must appear assigned for the parser to route require
                    // through the override.
                    GlobalVariable.globalGlobs.put(this.globName, true);

                    if (sourceGlobName == null) {
                        // Detached glob (e.g. returned by delete $stash->{name}):
                        // its slot values live on the glob object itself, not in
                        // GlobalVariable maps. Re-install those slots into the
                        // destination's slot-indexed maps directly (do NOT route
                        // through this.set(codeSlot), since that can self-assign
                        // the pinned CODE ref back onto itself via
                        // defineGlobalCodeRef -> getGlobalCodeRef returning the
                        // very same RuntimeScalar, which breaks refCount
                        // accounting for reference-type slots).
                        //
                        // This supports the common round-trip pattern:
                        //   my $saved = delete $stash->{name};
                        //   $stash->{name} = $saved;
                        if (sourceGlob.codeSlot != null) {
                            GlobalVariable.globalCodeRefs.put(this.globName, sourceGlob.codeSlot);
                            InheritanceResolver.invalidateCache();
                        }
                        if (sourceGlob.IO != null && sourceGlob.IO.getDefinedBoolean()) {
                            this.set(sourceGlob.IO);
                        }
                        if (sourceGlob.arraySlot != null) {
                            GlobalVariable.globalArrays.put(this.globName, sourceGlob.arraySlot);
                        }
                        if (sourceGlob.hashSlot != null) {
                            GlobalVariable.globalHashes.put(this.globName, sourceGlob.hashSlot);
                        }
                        if (sourceGlob.scalarSlot != null) {
                            GlobalVariable.globalVariables.put(this.globName, sourceGlob.scalarSlot);
                        }
                    } else {
                        // Copy all slots from source to destination
                        this.set(GlobalVariable.getGlobalCodeRef(sourceGlobName));
                        this.set(GlobalVariable.getGlobalIO(sourceGlobName));
                        this.set(GlobalVariable.getGlobalArray(sourceGlobName).createReference());
                        this.set(GlobalVariable.getGlobalHash(sourceGlobName).createReference());
                        this.set(GlobalVariable.getGlobalVariable(sourceGlobName).createReference());
                        this.set(GlobalVariable.getGlobalFormatRef(sourceGlobName));
                    }
                }
                return value;
            // Handle the case where a typeglob is assigned a reference to an array
            case HASHREFERENCE:
                if (value.value instanceof RuntimeHash) {
                    GlobalVariable.getGlobalHash(this.globName).setFromList(((RuntimeHash) value.value).getList());
                }
                return value;
            case UNDEF:
                // TODO: Add "Undefined value assigned to typeglob" warning with proper guards
                // to avoid false positives during module loading
                return value;
            case STRING:
            case BYTE_STRING:
                // Assigning a string to a stash entry sets the prototype for that symbol
                // e.g., $::{foo} = '$$' sets the prototype of &foo to '$$'
                RuntimeScalar codeRef = GlobalVariable.defineGlobalCodeRef(this.globName);
                if (codeRef.type == CODE) {
                    ((RuntimeCode) codeRef.value).prototype = value.toString();
                } else {
                    // Create a new RuntimeCode with the prototype
                    RuntimeCode code = new RuntimeCode(value.toString(), null);
                    codeRef.set(new RuntimeScalar(code));
                }
                return value;
            case GLOBREFERENCE:
                // `$stash->{foo} = \*bar` creates a constant subroutine returning the glob
                if (value.value instanceof RuntimeGlob glob) {
                    RuntimeCode code = new RuntimeCode("", null);
                    code.constantValue = new RuntimeList(new RuntimeScalar(glob));
                    GlobalVariable.defineGlobalCodeRef(this.globName).set(
                            new RuntimeScalar(code));
                } else if (value.value instanceof RuntimeIO runtimeIO) {
                    // *foo = *{$old}{IO} — restore the IO slot
                    RuntimeScalar ioSlot = GlobalVariable.getGlobalIO(this.globName);
                    ioSlot.type = RuntimeScalarType.GLOB;
                    ioSlot.value = runtimeIO;
                }
                return value;
        }
        throw new IllegalStateException("typeglob assignment not implemented for " + value.type);
    }

    /**
     * Sets the current RuntimeScalar object to the values associated with the given RuntimeGlob.
     * This method effectively implements the behavior of assigning one typeglob to another,
     * similar to Perl's typeglob assignment.
     *
     * @param value The RuntimeGlob object whose associated values are to be assigned.
     * @return The scalar value associated with the provided RuntimeGlob.
     */
    public RuntimeScalar set(RuntimeStashEntry value) {

        type = RuntimeScalarType.GLOB;

        // Retrieve the name of the glob from the provided RuntimeGlob object.
        String globName = value.globName;

        // Set the current scalar to the global code reference associated with the glob name.
        this.set(GlobalVariable.getGlobalCodeRef(globName));

        // Set the current scalar to the global IO (input/output) reference associated with the glob name.
        this.set(GlobalVariable.getGlobalIO(globName));

        // Set the current scalar to a reference of the global array associated with the glob name.
        this.set(GlobalVariable.getGlobalArray(globName).createReference());

        // Set the current scalar to a reference of the global hash associated with the glob name.
        this.set(GlobalVariable.getGlobalHash(globName).createReference());

        // Set the current scalar to a reference of the global variable associated with the glob name.
        this.set(GlobalVariable.getGlobalVariable(globName).createReference());

        // Set the current scalar to the global format reference associated with the glob name.
        this.set(GlobalVariable.getGlobalFormatRef(globName));

        // Return the scalar value associated with the provided RuntimeGlob.
        return value.scalar();
    }

    /**
     * Returns a boolean indicating whether the typeglob is defined.
     *
     * @return Always true, as typeglobs are always considered defined.
     */
    public boolean getDefinedBoolean() {
        // System.out.println("Stash Entry getDefinedBoolean: " + (type == RuntimeScalarType.UNDEF ? "undef" : "defined"));
        return type != RuntimeScalarType.UNDEF;
    }

    /**
     * Retrieves the boolean value of the typeglob.
     *
     * @return Always true, indicating the presence of the typeglob.
     */
    public boolean getBoolean() {
        // System.out.println("Stash Entry getBoolean: " + (type == RuntimeScalarType.UNDEF ? "undef" : "defined"));
        return getDefinedBoolean();
    }

    public RuntimeScalar defined() {
        // System.out.println("Stash Entry defined: " + (type == RuntimeScalarType.UNDEF ? "undef" : "defined"));
        return getDefinedBoolean() ? scalarTrue : scalarFalse;
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
     * Undefines the elements of the typeglob.
     * This method clears all slots (CODE, FORMAT, SCALAR, ARRAY, HASH) and invalidates the method resolution cache.
     *
     * @return The current RuntimeGlob instance after undefining its elements.
     */
    public RuntimeStashEntry undefine() {
        // Undefine CODE
        GlobalVariable.getGlobalCodeRef(this.globName).set(new RuntimeScalar());

        // Undefine FORMAT
        GlobalVariable.getGlobalFormatRef(this.globName).undefineFormat();

        // Undefine SCALAR
        GlobalVariable.getGlobalVariable(this.globName).set(new RuntimeScalar());

        // Undefine ARRAY - create empty array
        GlobalVariable.globalArrays.put(this.globName, new RuntimeArray());

        // Undefine HASH - create empty hash
        GlobalVariable.globalHashes.put(this.globName, new RuntimeHash());

        // Invalidate the method resolution cache
        InheritanceResolver.invalidateCache();

        type = RuntimeScalarType.UNDEF;

        return this;
    }

    /**
     * Returns a string representation of the stash entry.
     * For defined stash entries, returns the glob representation.
     * For undefined stash entries, returns undef.
     *
     * @return A string representation of the stash entry.
     */
    @Override
    public String toString() {
        // For stash entries, always return the glob representation
        // This matches Perl's behavior where stash entries stringify to "*PackageName::symbol"
        return "*" + this.globName;
    }

}
