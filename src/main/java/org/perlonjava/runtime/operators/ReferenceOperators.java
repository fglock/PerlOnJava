package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.perlmodule.Universal;
import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarEmptyString;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * Provides implementations of Perl's reference-related operators.
 * This class handles operations like blessing references, checking reference types,
 * and performing inheritance checks.
 */
public class ReferenceOperators {
    /**
     * "Blesses" a Perl reference into an object by associating it with a class name.
     * This method is used to convert a Perl reference into an object of a specified class.
     *
     * @param runtimeScalar The reference to bless
     * @param className     A RuntimeScalar representing the name of the class to bless the reference into
     * @return A RuntimeScalar representing the blessed object
     * @throws PerlCompilerException if attempting to bless a non-reference value
     */
    public static RuntimeScalar bless(RuntimeScalar runtimeScalar, RuntimeScalar className) {
        if (RuntimeScalarType.isReference(runtimeScalar)) {
            // Use toString() which invokes "" overloading for blessed objects.
            // Perl 5 throws "Attempt to bless into a reference" for non-overloaded
            // refs, but callers like IO::Handle already handle this via
            // ref($class) || $class in Perl code.
            String str = className.toString();
            // Default to "main" if className is empty
            if (str.isEmpty()) {
                str = "main";
            }
            // Canonicalise the class name through any stash aliases
            // (`*Foo:: = *Bar::`).  In Perl, `bless` binds the referent to
            // the stash object itself, whose `HvNAME` is the canonical
            // package name — so if Foo has been aliased to Bar, a later
            // `bless $x, "Foo"` reports `ref($x) eq "Bar"`.  Without this
            // canonicalisation, `ref` would return "Foo" and
            // `$x->isa("Bar")` would miss the linearised hierarchy that
            // the aliased stash exposes.
            str = GlobalVariable.resolveStashAlias(str);

            RuntimeBase referent = (RuntimeBase) runtimeScalar.value;
            int newBlessId = NameNormalizer.getBlessId(str);

            if (referent.refCount >= 0) {
                // Already-tracked referent (e.g., anonymous hash from `bless {}`).
                // Always keep tracking — even classes without DESTROY need
                // cascading cleanup of their hash/array elements when freed.
                if (referent.blessId == 0) {
                    // First bless of a tracked referent. Mortal-ize: bump refCount
                    // and queue a deferred decrement so that if the blessed ref is
                    // never stored in a named variable (method-chain temporaries like
                    // `Foo->new()->method()`), the flush brings refCount back to 0
                    // and fires DESTROY.  If the ref IS stored (the common
                    // `my $self = bless {}, $class` pattern), setLargeRefCounted()
                    // increments refCount first, so the mortal flush leaves it at the
                    // correct count.
                    referent.setBlessId(newBlessId);
                    referent.refCount++;  // 0 → 1 (or N → N+1 for edge cases)
                    MortalList.deferDecrement(referent);
                } else {
                    // Re-bless: update class, keep refCount.
                    referent.setBlessId(newBlessId);
                }
            } else {
                // First bless (or previously untracked)
                boolean wasAlreadyBlessed = referent.blessId != 0;
                referent.setBlessId(newBlessId);
                // Always activate tracking for blessed objects. Even without
                // DESTROY, we need cascading cleanup of hash/array elements
                // (e.g., Moo objects like BlockRunner that hold strong refs).

                // Retroactively count references stored in existing elements.
                // When the hash/array was created (e.g., bless { key => $ref }),
                // elements were stored while the container was untracked
                // (refCount == -1). Those stores did NOT increment referents'
                // refCounts. Now that we're transitioning to tracked, we must
                // count these as strong references so scopeExitCleanupHash
                // correctly decrements them when the container is destroyed.
                // Without this, references stored before bless are invisible to
                // cooperative refcounting, causing premature destruction of
                // objects held only by this container (e.g., DBIC ResultSource
                // held by a ResultSet's {result_source} hash element).
                if (referent instanceof RuntimeHash hash) {
                    for (RuntimeScalar elem : hash.elements.values()) {
                        RuntimeScalar.incrementRefCountForContainerStore(elem);
                    }
                } else if (referent instanceof RuntimeArray arr) {
                    for (RuntimeScalar elem : arr.elements) {
                        RuntimeScalar.incrementRefCountForContainerStore(elem);
                    }
                }

                if (wasAlreadyBlessed) {
                    // Re-bless from untracked class: the scalar being blessed
                    // already holds a reference that was never counted (because
                    // tracking wasn't active at assignment time). Count it as 1.
                    referent.refCount = 1;
                    runtimeScalar.refCountOwned = true;
                } else {
                    // First bless: start at refCount=1 and add to MortalList.
                    // The mortal entry will decrement back to 0 at the next
                    // statement-boundary flush (FREETMPS equivalent).
                    //
                    // If the blessed ref is stored in a named variable (the
                    // common `my $self = bless {}, $class` pattern), setLarge()
                    // increments refCount to 2. The mortal flush then brings it
                    // back to 1, which is correct: only the variable owns it.
                    //
                    // If the blessed ref is returned directly without storage
                    // (e.g., `sub new { bless {}, shift }`), the mortal entry
                    // ensures the object is properly cleaned up when the caller's
                    // statement boundary flushes, fixing method chain temporaries
                    // like `Foo->new()->method()` where the invocant was never
                    // tracked.
                    referent.refCount = 1;
                    MortalList.deferDecrement(referent);
                }
                // Activate the mortal mechanism
                MortalList.active = true;
            }
        } else {
            throw new PerlCompilerException("Can't bless non-reference value");
        }
        return runtimeScalar;
    }

    /**
     * Returns the type of a reference or its blessed package name.
     * Implements Perl's ref() built-in function.
     *
     * @param runtimeScalar The scalar to check
     * @return A RuntimeScalar containing the reference type as a string:
     * - "SCALAR", "ARRAY", "HASH", "CODE", "REF", "GLOB", "REGEXP", etc.
     * - The blessed package name if the reference is blessed
     * - Empty string if not a reference
     */
    public static RuntimeScalar ref(RuntimeScalar runtimeScalar) {
        // Handle special variables that need to compute their value
        if (runtimeScalar instanceof ScalarSpecialVariable specialVar) {
            return ref(specialVar.getValueAsScalar());
        }
        String str;
        int blessId;
        switch (runtimeScalar.type) {
            case TIED_SCALAR:
                str = ref(runtimeScalar.tiedFetch()).toString();
                break;
            case CODE:
                // ref() always returns "CODE" for CODE-typed scalars, regardless of whether
                // the subroutine is defined. In Perl, ref(\&stub) returns "CODE" even for
                // forward-declared subs without a body. The defined() check only matters
                // for defined(&name), not for ref().
                if (runtimeScalar.value == null) {
                    str = "CODE";
                } else {
                    blessId = ((RuntimeBase) runtimeScalar.value).blessId;
                    str = blessId == 0 ? "CODE" : NameNormalizer.getBlessStr(blessId);
                }
                break;
            case GLOB:
                // In Perl 5, ref(*glob) always returns "" (empty string) because a
                // bare glob is NOT a reference — it is a value type like a string or
                // number.  Only *references to* globs produce non-empty ref():
                //
                //   ref(*FH)   → ""       (bare glob — this case)
                //   ref(\*FH)  → "GLOB"   (handled by case REFERENCE → GLOB)
                //
                // Previously this case inspected which glob slots (scalar, array,
                // hash, code, IO, …) were populated and returned the slot type when
                // exactly one slot was filled.  That logic was wrong for bare globs
                // and caused Params::Validate::PP::_get_type() to misclassify globs
                // (e.g. *HANDLE with a CODE slot was reported as "CODE" instead of
                // falling through to the UNIVERSAL::isa(\$val,'GLOB') path).
                return scalarEmptyString;
            case REGEX:
                if (runtimeScalar.value == null) {
                    str = "Regexp";
                } else {
                    blessId = ((RuntimeBase) runtimeScalar.value).blessId;
                    str = blessId == 0 ? "Regexp" : NameNormalizer.getBlessStr(blessId);
                }
                break;
            case REFERENCE:
                // Handle nested references
                String ref = "REF";
                if (runtimeScalar.value instanceof RuntimeScalar scalar) {
                    ref = switch (scalar.type) {
                        case VSTRING -> "VSTRING";
                        case REGEX, ARRAYREFERENCE, HASHREFERENCE, CODE, GLOBREFERENCE, REFERENCE -> "REF";
                        case GLOB -> "GLOB";
                        case READONLY_SCALAR -> ref((RuntimeScalar) scalar.value).toString();
                        default -> "SCALAR";
                    };
                }
                if (runtimeScalar.value == null) {
                    str = ref;
                } else {
                    blessId = ((RuntimeBase) runtimeScalar.value).blessId;
                    str = blessId == 0 ? ref : NameNormalizer.getBlessStr(blessId);
                }
                break;
            case ARRAYREFERENCE:
                if (runtimeScalar.value == null) {
                    str = "ARRAY";
                } else {
                    blessId = ((RuntimeBase) runtimeScalar.value).blessId;
                    str = blessId == 0 ? "ARRAY" : NameNormalizer.getBlessStr(blessId);
                }
                break;
            case HASHREFERENCE:
                if (runtimeScalar.value == null) {
                    str = "HASH";
                } else {
                    blessId = ((RuntimeBase) runtimeScalar.value).blessId;
                    str = blessId == 0 ? "HASH" : NameNormalizer.getBlessStr(blessId);
                }
                break;
            case GLOBREFERENCE:
                if (runtimeScalar.value == null) {
                    str = "GLOB";
                } else if (runtimeScalar.value instanceof RuntimeIO) {
                    // IO slot access (*{$fh}{IO}) returns IO::Handle class in Perl 5
                    blessId = ((RuntimeBase) runtimeScalar.value).blessId;
                    str = blessId == 0 ? "IO::Handle" : NameNormalizer.getBlessStr(blessId);
                } else {
                    blessId = ((RuntimeBase) runtimeScalar.value).blessId;
                    str = blessId == 0 ? "GLOB" : NameNormalizer.getBlessStr(blessId);
                }
                break;
            case FORMAT:
                str = "FORMAT";
                break;
            case READONLY_SCALAR:
                return ref((RuntimeScalar) runtimeScalar.value);
            default:
                return scalarEmptyString;
        }
        return new RuntimeScalar(str);
    }

    /**
     * Implements Perl's isa operator to check if an object belongs to a class or inherits from it.
     * Delegates the actual check to Universal.isa().
     *
     * @param runtimeScalar The object to check
     * @param className     The class name to check against
     * @return A RuntimeScalar containing the boolean result of the inheritance check
     */
    public static RuntimeScalar isa(RuntimeScalar runtimeScalar, RuntimeScalar className) {
        RuntimeArray args = new RuntimeArray();

        if (blessedId(runtimeScalar) != 0) {
            RuntimeArray.push(args, className);
            return RuntimeCode.call(
                    runtimeScalar,
                    new RuntimeScalar("isa"),
                    null,
                    args,
                    RuntimeContextType.SCALAR).scalar();
        }

        RuntimeArray.push(args, runtimeScalar);
        RuntimeArray.push(args, className);
        return Universal.isa(args, RuntimeContextType.SCALAR).scalar();
    }
}
