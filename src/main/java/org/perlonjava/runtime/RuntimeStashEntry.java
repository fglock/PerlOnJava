package org.perlonjava.runtime;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.RuntimeScalarType.*;

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
        // System.out.println("Stash Entry set " + globName + " to " + value.type);

        type = RuntimeScalarType.GLOB;
        if (value.type == REFERENCE) {
            if (value.value instanceof RuntimeScalar) {
                RuntimeCode code = new RuntimeCode("", null);
                code.constantValue = value.scalarDeref().getList();
                GlobalVariable.getGlobalCodeRef(this.globName).set(
                        new RuntimeScalar(code));
            }
            return value;
        }
        if (value.type == ARRAYREFERENCE) {
            if (value.value instanceof RuntimeArray) {
                RuntimeCode code = new RuntimeCode("", null);
                code.constantValue = value.arrayDeref().getList();
                GlobalVariable.getGlobalCodeRef(this.globName).set(
                        new RuntimeScalar(code));
            }
            return value;
        }


        switch (value.type) {
            case CODE:
                GlobalVariable.getGlobalCodeRef(this.globName).set(value);

                // Invalidate the method resolution cache
                InheritanceResolver.invalidateCache();

                return value;
            case GLOB:
                if (value.value instanceof RuntimeIO) {
                    // *STDOUT = $new_handle
                    GlobalVariable.getGlobalIO(this.globName).set(value);
                }
                return value;
            // Handle the case where a typeglob is assigned a reference to an array
            case HASHREFERENCE:
                if (value.value instanceof RuntimeHash) {
                    GlobalVariable.getGlobalHash(this.globName).setFromList(((RuntimeHash) value.value).getList());
                }
                return value;
            case UNDEF:
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
     * This method clears the CODE reference and invalidates the method resolution cache.
     *
     * @return The current RuntimeGlob instance after undefining its elements.
     */
    public RuntimeStashEntry undefine() {
        // Undefine CODE
        GlobalVariable.getGlobalCodeRef(this.globName).set(new RuntimeScalar());

        // Invalidate the method resolution cache
        InheritanceResolver.invalidateCache();

        type = RuntimeScalarType.UNDEF;

        // XXX TODO undefine scalar, array, hash
        return this;
    }

}
