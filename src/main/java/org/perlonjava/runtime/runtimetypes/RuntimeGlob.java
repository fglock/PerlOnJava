package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.operators.WarnDie;

import java.util.Iterator;
import java.util.Stack;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * Represents a runtime typeglob in Perl. Typeglobs are special symbols that can represent
 * all types of Perl variables (scalars, arrays, hashes, subroutines, filehandles) with the same name.
 * This class provides methods to manipulate and interact with typeglobs in the runtime environment.
 */
public class RuntimeGlob extends RuntimeScalar implements RuntimeScalarReference {

    private static final Stack<GlobSlotSnapshot> globSlotStack = new Stack<>();
    // The name of the typeglob
    public String globName;
    public RuntimeScalar IO;
    // Local scalar slot for anonymous globs (when globName is null)
    private RuntimeScalar scalarSlot;
    // Local array slot for anonymous globs (when globName is null)
    private RuntimeArray arraySlot;
    // Local hash slot for anonymous globs (when globName is null)
    private RuntimeHash hashSlot;
    // Local code slot for detached globs (from stash delete)
    public RuntimeScalar codeSlot;

    /**
     * Tracks how many RuntimeScalar variables hold a GLOBREFERENCE to this glob.
     * Used by scopeExitCleanup to avoid closing IO when other variables still
     * reference the same glob. Starts at 0 (before any variable holds it).
     * Incremented in RuntimeScalar.setLarge() when a GLOBREFERENCE is assigned,
     * decremented in scopeExitCleanup(). IO is only closed when this reaches 0.
     */
    public int ioHolderCount = 0;

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

    /**
     * Creates a detached copy of this glob that has its own independent IO slot.
     * 
     * <p>This is crucial for the {@code do { local *GLOB; *GLOB }} pattern used to create
     * anonymous filehandles. When you do:
     * <pre>
     *   my $fh = do { local *FH; open FH, ...; *FH };
     * </pre>
     * The returned glob must retain the IO that was opened, even after the local scope
     * ends and restores the global *FH. This method creates a new RuntimeGlob that:
     * <ul>
     *   <li>Has the same globName (for stringification)</li>
     *   <li>Shares the CURRENT IO RuntimeScalar reference, so that opening a file
     *       on the original glob also affects this copy</li>
     * </ul>
     * 
     * <p><b>IMPORTANT:</b> The copy shares the same IO RuntimeScalar object as the
     * original at the time of copying. This means:
     * <ul>
     *   <li>If you call {@code setIO()} on the original, it modifies the shared IO in place,
     *       so the copy sees the change</li>
     *   <li>When {@code local} restores the original glob's IO reference (via
     *       {@code this.IO = savedIO}), the copy's IO reference is NOT affected because
     *       it's a separate field</li>
     * </ul>
     * 
     * <p>Subclasses (like RuntimeStashEntry) should override this to return the same
     * instance, preserving their special ref() behavior.
     *
     * @return A new RuntimeGlob with the same globName and IO reference.
     */
    public RuntimeGlob createDetachedCopy() {
        RuntimeGlob copy = new RuntimeGlob(this.globName);
        copy.IO = this.IO;  // Share the current IO reference
        // Detached copies get their own hash/array/scalar slots so that
        // patterns like \do { local *FH } have per-instance storage.
        // This is used by IO::Scalar which stores state via *$self->{Key}.
        copy.hashSlot = new RuntimeHash();
        return copy;
    }

    /**
     * Creates a detached glob with pre-populated local slots.
     * Used by stash delete to return a glob that holds the old slot values
     * after they've been removed from GlobalVariable.
     * Uses globName=null so getGlobSlot() reads from local slots.
     */
    public static RuntimeGlob createDetachedWithSlots(
            RuntimeScalar scalar, RuntimeArray array, RuntimeHash hash, RuntimeGlob io,
            RuntimeScalar code) {
        RuntimeGlob glob = new RuntimeGlob(null);
        glob.scalarSlot = scalar != null ? scalar : new RuntimeScalar();
        glob.arraySlot = array;
        glob.hashSlot = hash;
        glob.codeSlot = code;
        if (io != null) {
            glob.IO = io.IO;
        }
        return glob;
    }

    /**
     * Overload without code parameter for backward compatibility.
     */
    public static RuntimeGlob createDetachedWithSlots(
            RuntimeScalar scalar, RuntimeArray array, RuntimeHash hash, RuntimeGlob io) {
        return createDetachedWithSlots(scalar, array, hash, io, null);
    }

    /**
     * Returns a hash code based on the glob name.
     * This ensures that all copies of the same glob (including detached copies)
     * have the same hash code, which is necessary for correct stringification
     * and equality comparisons in Perl code like `$_[0] eq \*FOO`.
     * Anonymous globs (null name) use identity hash for uniqueness.
     *
     * @return Hash code based on the glob name, or identity hash for anonymous globs
     */
    @Override
    public int hashCode() {
        return globName != null ? globName.hashCode() : System.identityHashCode(this);
    }

    /**
     * Checks equality based on glob name.
     * Two RuntimeGlob objects are equal if they have the same globName.
     * This ensures that detached copies compare equal to the original glob.
     * Anonymous globs (null name) use identity equality only.
     *
     * @param obj The object to compare
     * @return true if both are RuntimeGlob with the same globName
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RuntimeGlob other)) return false;
        return globName != null && globName.equals(other.globName);
    }

    public static boolean isGlobAssigned(String globName) {
        return GlobalVariable.globalGlobs.getOrDefault(globName, false);
    }

    /**
     * Checks if this glob has any defined content in any slot.
     * Used for `defined *glob` which returns true if any slot (scalar, array, hash, code, io, format) is initialized.
     * Note: For arrays/hashes, existence of the slot = defined (even if empty).
     *
     * @return RuntimeScalar true if any slot has content, false otherwise.
     */
    public RuntimeScalar defined() {
        // Check if the glob has been assigned (any slot has content)
        if (GlobalVariable.globalGlobs.getOrDefault(this.globName, false)) {
            return RuntimeScalarCache.scalarTrue;
        }
        // Check scalar slot - must have defined value
        if (GlobalVariable.globalVariables.containsKey(this.globName)) {
            RuntimeScalar scalar = GlobalVariable.globalVariables.get(this.globName);
            if (scalar != null && scalar.getDefinedBoolean()) {
                return RuntimeScalarCache.scalarTrue;
            }
        }
        // Check array slot - exists = defined (even if empty)
        if (GlobalVariable.globalArrays.containsKey(this.globName)) {
            return RuntimeScalarCache.scalarTrue;
        }
        // Check hash slot - exists = defined (even if empty)
        if (GlobalVariable.globalHashes.containsKey(this.globName)) {
            return RuntimeScalarCache.scalarTrue;
        }
        // Check code slot - must have defined value
        if (GlobalVariable.globalCodeRefs.containsKey(this.globName)) {
            RuntimeScalar code = GlobalVariable.globalCodeRefs.get(this.globName);
            if (code != null && code.getDefinedBoolean()) {
                return RuntimeScalarCache.scalarTrue;
            }
        }
        // Check IO slot
        if (this.IO != null && this.IO.getDefinedBoolean()) {
            return RuntimeScalarCache.scalarTrue;
        }
        return RuntimeScalarCache.scalarFalse;
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

        switch (value.type) {
            case TIED_SCALAR:
                return set(value.tiedFetch());
            case READONLY_SCALAR:
                return set((RuntimeScalar) value.value);
            case CODE:
                GlobalVariable.defineGlobalCodeRef(this.globName).set(value);

                // Invalidate the method resolution cache
                InheritanceResolver.invalidateCache();

                // Increment package generation counter for mro::get_pkg_gen
                int lastColonIdx = this.globName.lastIndexOf("::");
                if (lastColonIdx > 0) {
                    String pkgName = this.globName.substring(0, lastColonIdx);
                    org.perlonjava.runtime.perlmodule.Mro.incrementPackageGeneration(pkgName);
                }

                return value;
            case GLOB, GLOBREFERENCE:
                // *STDOUT = $new_handle
                if (value.value instanceof RuntimeGlob runtimeGlob) {
                    this.set(runtimeGlob);
                } else if (value.value instanceof RuntimeIO runtimeIO) {
                    // *glob = *{$old}{IO} — restore the IO slot
                    this.setIO(runtimeIO);
                }
                return value;
            case ARRAYREFERENCE:
                // Handle the case where a typeglob is assigned a reference to an array
                // `*foo = \@bar` creates an alias - both names refer to the same array
                // Also update all glob aliases
                if (value.value instanceof RuntimeArray arr) {
                    for (String aliasedName : GlobalVariable.getGlobAliasGroup(this.globName)) {
                        GlobalVariable.globalArrays.put(aliasedName, arr);
                    }
                    // Mark as explicitly declared for strict vars (e.g., Exporter imports)
                    GlobalVariable.declareGlobalArray(this.globName);
                }
                return value;
            case HASHREFERENCE:
                // `*foo = \%bar` creates an alias - both names refer to the same hash
                // Also update all glob aliases
                if (value.value instanceof RuntimeHash hash) {
                    for (String aliasedName : GlobalVariable.getGlobAliasGroup(this.globName)) {
                        GlobalVariable.globalHashes.put(aliasedName, hash);
                    }
                    // Mark as explicitly declared for strict vars (e.g., Exporter imports)
                    GlobalVariable.declareGlobalHash(this.globName);
                }
                return value;
            case REFERENCE:
                // `*foo = \$bar` aliases the SCALAR slot to $bar, regardless of what $bar contains.
                // This must replace the scalar container (alias) rather than storing into
                // the existing scalar, otherwise tied scalars would invoke STORE.
                // Note: \@array and \%hash come in as ARRAYREFERENCE/HASHREFERENCE types,
                // not REFERENCE, so they are handled above in their respective cases.
                if (value.value instanceof RuntimeScalar) {
                    GlobalVariable.aliasGlobalVariable(this.globName, (RuntimeScalar) value.value);
                    // Mark as explicitly declared for strict vars (e.g., Exporter imports)
                    GlobalVariable.declareGlobalVariable(this.globName);
                }
                return value;
            case UNDEF:
                // TODO: Add "Undefined value assigned to typeglob" warning with proper guards
                // to avoid false positives during module loading
                return value;
            case INTEGER:
            case DOUBLE:
            case STRING:
            case BYTE_STRING:
            case BOOLEAN:
            case VSTRING:
            case DUALVAR:
                // Handle scalar value assignments to typeglobs
                // This replaces the scalar slot of the typeglob.
                // If the current scalar is read-only (e.g., aliased from a for-loop
                // iterating over literal constants), replace it with a new mutable
                // scalar rather than modifying in-place. In Perl 5, *foo = "value"
                // replaces the GvSV slot, not modifies the existing SV in-place.
                RuntimeScalar currentScalar = GlobalVariable.getGlobalVariable(this.globName);
                if (currentScalar instanceof RuntimeScalarReadOnly) {
                    RuntimeScalar newScalar = new RuntimeScalar();
                    newScalar.set(value);
                    GlobalVariable.aliasGlobalVariable(this.globName, newScalar);
                } else {
                    currentScalar.set(value);
                }
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

        // Anonymous globs (from "open my $fh, ...") have no global name.
        // Just copy the IO slot — no aliasing needed.
        // This matches Perl 5: *STDOUT = $lexical_fh only replaces the IO slot.
        if (value.globName == null) {
            // Save old IO for selectedHandle check (needed for local *STDOUT = $fh)
            RuntimeIO oldRuntimeIO = null;
            if (this.IO != null && this.IO.value instanceof RuntimeIO rio) {
                oldRuntimeIO = rio;
            }

            this.IO = value.IO;
            // Also update the global IO entry for this glob
            if (this.globName != null) {
                RuntimeGlob targetIO = GlobalVariable.getGlobalIO(this.globName);
                targetIO.IO = value.IO;
            }

            // Update selectedHandle if the old IO was the currently selected output handle.
            // This ensures that `local *STDOUT = $fh` redirects bare `print` (no filehandle)
            // to the new handle, not just explicit `print STDOUT`.
            if (oldRuntimeIO != null && oldRuntimeIO == RuntimeIO.selectedHandle
                    && value.IO != null && value.IO.value instanceof RuntimeIO newRIO) {
                RuntimeIO.selectedHandle = newRIO;
            }

            return value.scalar();
        }

        if (this.globName.endsWith("::") && value.globName.endsWith("::")) {
            GlobalVariable.setStashAlias(this.globName, value.globName);
            InheritanceResolver.invalidateCache();
            GlobalVariable.clearPackageCache();
            return value.scalar();
        }

        // Register glob alias so future slot assignments affect both globs
        GlobalVariable.setGlobAlias(this.globName, value.globName);

        // Retrieve the RuntimeScalar value associated with the provided RuntimeGlob.
        RuntimeScalar result = value.scalar();

        // Retrieve the name of the glob from the provided RuntimeGlob object.
        String globName = value.globName;

        // Create ALIASES by making both names point to the same objects in the global maps
        // This is the key difference from the old implementation which created references

        // Alias the CODE slot: Update the existing RuntimeScalar's value instead of replacing it.
        // This is critical because compiled code may have cached references to the existing
        // RuntimeScalar at compile time. Replacing the map entry would leave cached references
        // pointing to the old (now orphaned) RuntimeScalar, causing calls to fail after
        // the stash entry is deleted.
        RuntimeScalar sourceCode = GlobalVariable.getGlobalCodeRef(globName);
        RuntimeScalar targetCode = GlobalVariable.getGlobalCodeRef(this.globName);
        targetCode.set(sourceCode);  // Copy value into existing RuntimeScalar
        // Invalidate the method resolution cache
        InheritanceResolver.invalidateCache();

        // Alias the IO slot: both names point to the same IO object
        // Must update BOTH this.IO (for detached copies) AND the global glob's IO
        RuntimeGlob sourceIO = GlobalVariable.getGlobalIO(globName);
        RuntimeGlob targetIO = GlobalVariable.getGlobalIO(this.globName);

        // Save old IO for selectedHandle check (needed for local *STDOUT = *OTHER)
        RuntimeIO oldRuntimeIO = null;
        if (this.IO != null && this.IO.value instanceof RuntimeIO rio) {
            oldRuntimeIO = rio;
        }

        this.IO = sourceIO.IO;
        targetIO.IO = sourceIO.IO;

        // Update selectedHandle if the old IO was the currently selected output handle
        if (oldRuntimeIO != null && oldRuntimeIO == RuntimeIO.selectedHandle
                && sourceIO.IO != null && sourceIO.IO.value instanceof RuntimeIO newRIO) {
            RuntimeIO.selectedHandle = newRIO;
        }

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
     * Get a typeglob slot (CODE, SCALAR, ARRAY, HASH, IO, FORMAT).
     * This implements the *glob{SLOT} syntax for accessing glob slots by name.
     * Note: This is NOT the same as *glob->{Key} which accesses the glob's hash slot.
     * The two operations are distinguished at the compiler level.
     *
     * @param index The scalar representing the slot name to access.
     * @return A RuntimeScalar representing the slot value or reference. If the slot name
     * is not recognized, an empty RuntimeScalar is returned.
     */
    public RuntimeScalar getGlobSlot(RuntimeScalar index) {
        return switch (index.toString()) {
            case "CODE" -> {
                // For detached globs (null globName, from stash delete), use local code slot
                if (this.globName == null) {
                    yield this.codeSlot != null ? this.codeSlot : new RuntimeScalar();
                }
                // Only return CODE ref if it's in the stash (globalCodeRefs).
                // We must NOT use getGlobalCodeRef() here because it returns pinned
                // references that survive stash deletion. When checking *{glob}{CODE},
                // we want to see if the sub is actually in the stash, not if it was
                // ever defined and pinned. This is critical for Moo's bootstrap
                // mechanism where a sub deletes itself from the stash.
                RuntimeScalar codeRef = GlobalVariable.globalCodeRefs.get(this.globName);
                if (codeRef != null && codeRef.type == RuntimeScalarType.CODE && codeRef.value instanceof RuntimeCode code) {
                    if (code.defined() || code.isDeclared) {
                        yield codeRef;
                    }
                }
                yield new RuntimeScalar(); // Return undef if code doesn't exist
            }
            case "PACKAGE" -> {
                // Return the package that owns this glob.
                if (this.globName == null) yield new RuntimeScalar();
                int lastColonIndex = this.globName.lastIndexOf("::");
                String pkg = lastColonIndex >= 0 ? this.globName.substring(0, lastColonIndex) : "main";
                yield new RuntimeScalar(NameNormalizer.getBlessStrForClassName(pkg));
            }
            case "NAME" -> {
                // Return the name of this glob (without the package prefix)
                if (this.globName == null) yield new RuntimeScalar();
                int lastColonIndex = this.globName.lastIndexOf("::");
                String name = lastColonIndex >= 0 ? this.globName.substring(lastColonIndex + 2) : this.globName;
                yield new RuntimeScalar(name);
            }
            case "IO", "FILEHANDLE" -> {
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
            case "SCALAR" -> {
                // For anonymous globs (null globName), use local scalarSlot
                if (this.globName == null) {
                    if (this.scalarSlot == null) {
                        this.scalarSlot = new RuntimeScalar();
                    }
                    yield this.scalarSlot;
                }
                yield GlobalVariable.getGlobalVariable(this.globName);
            }
            case "ARRAY" -> {
                // For anonymous globs (null globName), use local arraySlot
                if (this.globName == null) {
                    if (this.arraySlot == null) {
                        this.arraySlot = new RuntimeArray();
                    }
                    yield this.arraySlot.createReference();
                }
                // Only return reference if array exists (has elements or was explicitly created)
                if (GlobalVariable.existsGlobalArray(this.globName)) {
                    yield GlobalVariable.getGlobalArray(this.globName).createReference();
                }
                yield new RuntimeScalar(); // Return undef if array doesn't exist
            }
            case "HASH" -> {
                // For *glob{HASH} (glob slot access), always use the global hash.
                // The local hashSlot is only for *glob->{key} (arrow hash deref via getGlobHash()).
                if (this.globName == null) {
                    // Anonymous globs use local hashSlot since they have no global name
                    if (this.hashSlot == null) {
                        this.hashSlot = new RuntimeHash();
                    }
                    yield this.hashSlot.createReference();
                }
                // Only return reference if hash exists (has elements or was explicitly created)
                if (GlobalVariable.existsGlobalHash(this.globName)) {
                    yield GlobalVariable.getGlobalHash(this.globName).createReference();
                }
                yield new RuntimeScalar(); // Return undef if hash doesn't exist
            }
            case "FORMAT" -> GlobalVariable.getGlobalFormatRef(this.globName);
            case "GLOB" -> {
                // *glob{GLOB} returns a reference to the glob itself (\*glob)
                yield this.createReference();
            }
            default -> new RuntimeScalar();
        };
    }

    public RuntimeScalar getIO() {
        return this.IO;
    }

    /**
     * Get the hash slot for this glob.
     * Prefers the local hashSlot if it has been set (for detached copies and anonymous globs).
     * For named globs without a local hashSlot, retrieves from GlobalVariable.
     */
    public RuntimeHash getGlobHash() {
        if (this.hashSlot != null) {
            return this.hashSlot;
        }
        if (this.globName == null) {
            this.hashSlot = new RuntimeHash();
            return this.hashSlot;
        }
        return GlobalVariable.getGlobalHash(this.globName);
    }

    /**
     * Get the array slot for this glob.
     * For anonymous globs (null globName), uses the local arraySlot field.
     * For named globs, retrieves from GlobalVariable.
     */
    public RuntimeArray getGlobArray() {
        if (this.globName == null) {
            if (this.arraySlot == null) {
                this.arraySlot = new RuntimeArray();
            }
            return this.arraySlot;
        }
        return GlobalVariable.getGlobalArray(this.globName);
    }

    public RuntimeGlob setIO(RuntimeScalar io) {
        // Check if the current IO is the selected handle - if so, update it
        RuntimeIO oldIO = null;
        if (this.IO.value instanceof RuntimeIO) {
            oldIO = (RuntimeIO) this.IO.value;
        }
        // If IO slot is tied (TIED_SCALAR with TieHandle), replace it entirely
        // Otherwise use set() to modify in place, preserving sharing with detached copies
        if (this.IO.type == RuntimeScalarType.TIED_SCALAR) {
            this.IO = io;
        } else {
            this.IO.type = io.type;
            this.IO.value = io.value;
        }
        // If the IO scalar contains a RuntimeIO, set its glob name
        if (io.value instanceof RuntimeIO runtimeIO) {
            runtimeIO.globName = this.globName;
            // Update selectedHandle if the old IO was the selected handle
            if (oldIO != null && oldIO == RuntimeIO.selectedHandle) {
                RuntimeIO.selectedHandle = runtimeIO;
            }
        }
        return this;
    }

    public RuntimeGlob setIO(RuntimeIO io) {
        // Set the glob name in the RuntimeIO for proper stringification
        io.globName = this.globName;
        // Check if the current IO is the selected handle - if so, update it
        RuntimeIO oldIO = null;
        if (this.IO.value instanceof RuntimeIO) {
            oldIO = (RuntimeIO) this.IO.value;
        }
        // If IO slot is tied (TIED_SCALAR with TieHandle), replace it entirely
        // Otherwise modify in place, preserving sharing with detached copies
        if (this.IO.type == RuntimeScalarType.TIED_SCALAR) {
            this.IO = new RuntimeScalar(io);
        } else {
            this.IO.type = RuntimeScalarType.GLOB;  // RuntimeIO is stored as GLOB type
            this.IO.value = io;
        }
        // Update selectedHandle if the old IO was the selected handle
        // This ensures that when STDOUT is redirected, print without explicit
        // filehandle uses the new handle
        if (oldIO != null && oldIO == RuntimeIO.selectedHandle) {
            RuntimeIO.selectedHandle = io;
        }
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
     * This is the unsigned interpretation of the hash code.
     * Note: This may overflow for hash codes > Integer.MAX_VALUE, but
     * getDoubleRef() returns the correct unsigned value.
     *
     * @return The hash code of this instance as unsigned (may overflow).
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Returns a double representation of the typeglob reference.
     * This is the unsigned interpretation of the hash code, matching what
     * hex() would return from the stringified address in toStringRef().
     *
     * @return The hash code as an unsigned value (as double).
     */
    public double getDoubleRef() {
        return Integer.toUnsignedLong(this.hashCode());
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
     * This method clears all slots (CODE, FORMAT, SCALAR, ARRAY, HASH) and invalidates the method resolution cache.
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

        // Undefine SCALAR
        GlobalVariable.getGlobalVariable(this.globName).set(new RuntimeScalar());

        // Undefine ARRAY - create empty array
        GlobalVariable.globalArrays.put(this.globName, new RuntimeArray());

        // Undefine HASH - create empty hash
        GlobalVariable.globalHashes.put(this.globName, new RuntimeHash());

        return this;
    }

    @Override
    public void dynamicSaveState() {
        RuntimeScalar savedScalar = GlobalVariable.getGlobalVariable(this.globName);
        RuntimeArray savedArray = GlobalVariable.getGlobalArray(this.globName);
        RuntimeHash savedHash = GlobalVariable.getGlobalHash(this.globName);
        RuntimeScalar savedCode = GlobalVariable.getGlobalCodeRef(this.globName);
        // Save the current IO object reference (not its state) so we can restore it later.
        // This allows captured glob references to keep the "local" IO even after restore.
        RuntimeScalar savedIO = this.IO;
        // Save selectedHandle if this glob's IO is the currently selected handle.
        // This is needed for local(*STDOUT) to correctly restore selectedHandle
        // after Capture::Tiny or similar modules localize STDOUT.
        RuntimeIO savedSelectedHandle = null;
        boolean isSelectedHandle = false;
        if (this.IO != null && this.IO.value instanceof RuntimeIO rio && rio == RuntimeIO.selectedHandle) {
            savedSelectedHandle = RuntimeIO.selectedHandle;
            isSelectedHandle = true;
        } else if (this.IO != null && this.IO.type == TIED_SCALAR && this.IO.value == RuntimeIO.selectedHandle) {
            savedSelectedHandle = RuntimeIO.selectedHandle;
            isSelectedHandle = true;
        }
        globSlotStack.push(new GlobSlotSnapshot(this.globName, savedScalar, savedArray, savedHash, savedCode, savedIO, savedSelectedHandle));

        // Replace global table entries with NEW empty objects instead of mutating the
        // existing ones in-place. This is critical because the existing objects may be
        // aliased (e.g., via *glob = $blessed_ref), and calling dynamicSaveState() on
        // them would clear/corrupt the original blessed reference's data.
        GlobalVariable.globalVariables.put(this.globName, new RuntimeScalar());
        GlobalVariable.globalArrays.put(this.globName, new RuntimeArray());
        GlobalVariable.globalHashes.put(this.globName, new RuntimeHash());
        RuntimeScalar newCode = new RuntimeScalar();
        GlobalVariable.globalCodeRefs.put(this.globName, newCode);
        // Also redirect pinnedCodeRefs to the new empty code for the local scope.
        // Without this, getGlobalCodeRef() returns the saved (pinned) object, and
        // assignments during the local scope would mutate the saved snapshot instead
        // of the new empty code, making the restore a no-op.
        GlobalVariable.replacePinnedCodeRef(this.globName, newCode);
        GlobalVariable.getGlobalFormatRef(this.globName).dynamicSaveState();

        // Create a NEW RuntimeGlob for the local scope and install it in globalIORefs.
        // This matches Perl 5 behavior where `local *FH` replaces the stash entry with a fresh GV.
        // References captured during the local scope (e.g. \do { local *FH }) will point to the
        // new glob, which remains valid after the local scope ends and this old glob is restored.
        RuntimeGlob newGlob = new RuntimeGlob(this.globName);
        // Give the new glob its own hash/array/scalar slots so that orphaned globs
        // (captured via \do { local *FH }) have independent per-instance storage.
        // This is needed by IO::Scalar which stores state via *$self->{Key}.
        newGlob.hashSlot = new RuntimeHash();

        // If the old glob's IO was the selected handle, initialize the new glob
        // with a stub RuntimeIO and point selectedHandle to it. This way, when
        // open(*STDOUT, ...) later calls setIO on the new glob, it will see
        // oldIO == selectedHandle and correctly update selectedHandle to the new IO.
        // This ensures that `print` without explicit filehandle follows the
        // localized glob (matching Perl 5 name-based resolution).
        if (isSelectedHandle) {
            RuntimeIO stubIO = new RuntimeIO();
            stubIO.globName = this.globName;
            newGlob.IO = new RuntimeScalar(stubIO);
            RuntimeIO.selectedHandle = stubIO;
        }

        GlobalVariable.globalIORefs.put(this.globName, newGlob);
    }

    @Override
    public void dynamicRestoreState() {
        GlobSlotSnapshot snap = globSlotStack.pop();

        // Restore the saved IO object reference on this (old) glob.
        this.IO = snap.io;

        // Restore selectedHandle if it was saved during dynamicSaveState.
        // This ensures that after local(*STDOUT) + restore, print without explicit
        // filehandle goes through the correct (possibly tied) handle.
        if (snap.savedSelectedHandle != null) {
            RuntimeIO.selectedHandle = snap.savedSelectedHandle;
        }

        // Put this (old) glob back in globalIORefs, replacing the local scope's glob.
        // Any references captured during the local scope still point to the local glob,
        // which is now an independent orphaned glob (matching Perl 5 GV behavior).
        GlobalVariable.globalIORefs.put(snap.globName, this);

        // Restore saved objects directly - they were never mutated, so no
        // dynamicRestoreState() call is needed.
        GlobalVariable.globalVariables.put(snap.globName, snap.scalar);
        GlobalVariable.globalHashes.put(snap.globName, snap.hash);
        GlobalVariable.globalArrays.put(snap.globName, snap.array);
        GlobalVariable.globalCodeRefs.put(snap.globName, snap.code);
        // Also restore the pinned code ref so getGlobalCodeRef() returns the
        // original code object again.
        GlobalVariable.replacePinnedCodeRef(snap.globName, snap.code);
        InheritanceResolver.invalidateCache();

        GlobalVariable.getGlobalFormatRef(snap.globName).dynamicRestoreState();
    }

    private record GlobSlotSnapshot(
            String globName,
            RuntimeScalar scalar,
            RuntimeArray array,
            RuntimeHash hash,
            RuntimeScalar code,
            RuntimeScalar io,
            RuntimeIO savedSelectedHandle) {
    }
}
