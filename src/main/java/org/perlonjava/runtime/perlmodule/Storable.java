package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ModuleOperators;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;
import org.perlonjava.runtime.mro.InheritanceResolver;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

/**
 * Storable module implementation using the native Perl Storable wire
 * format. The reader/writer live in the {@code .storable} subpackage;
 * this class is the public Perl-facing entry point that dispatches
 * {@code freeze}/{@code nfreeze}/{@code thaw}/{@code store}/{@code nstore}
 * /{@code retrieve}/{@code dclone} into them.
 */
public class Storable extends PerlModuleBase {

    /**
     * Constructor for Storable module.
     */
    public Storable() {
        super("Storable", false);
    }

    /**
     * Initializes the Storable module.
     */
    public static void initialize() {
        Storable storable = new Storable();
        try {
            storable.registerMethod("freeze", null);
            storable.registerMethod("thaw", null);
            storable.registerMethod("nfreeze", null);
            storable.registerMethod("store", null);
            storable.registerMethod("retrieve", null);
            storable.registerMethod("nstore", null);
            storable.registerMethod("dclone", null);
            storable.registerMethod("last_op_in_netorder", null);

            storable.defineExport("EXPORT", "store", "retrieve", "nstore", "freeze", "thaw", "nfreeze", "dclone");

        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Storable method: " + e.getMessage());
        }
    }

    // Storable type bytes matching Perl 5's sort order.
    // The numeric values determine serialization sort order for DBIC's
    // condition deduplication (serialize() → nfreeze() → hash keys → sort).
    // (Constants are kept here for documentation; the live encoder/decoder
    // uses the canonical copy in {@code .storable.Opcodes}.)

    // Tracks whether the last freeze/store operation used network byte order.
    // Set true by nfreeze()/nstore(); set false by freeze()/store().
    // Exposed via Storable::last_op_in_netorder().
    private static volatile boolean lastOpInNetorder = false;

    /**
     * Returns 1 if the last freeze/store operation used network byte order
     * (i.e. was nfreeze or nstore), 0 otherwise.
     */
    public static RuntimeList last_op_in_netorder(RuntimeArray args, int ctx) {
        return new RuntimeScalar(lastOpInNetorder ? 1 : 0).getList();
    }

    /**
     * Freezes data to the native Perl Storable in-memory format. Output
     * starts with the {@code (major&lt;&lt;1)|netorder} flag byte followed
     * by the minor version byte (no {@code pst0} prefix; that's only for
     * file mode), then the body — same wire format upstream Perl
     * {@code freeze} produces.
     */
    public static RuntimeList freeze(RuntimeArray args, int ctx) {
        return freezeImpl(args, false);
    }

    private static RuntimeList freezeImpl(RuntimeArray args, boolean netorder) {
        lastOpInNetorder = netorder;
        if (args.isEmpty()) {
            return WarnDie.die(new RuntimeScalar("freeze: not enough arguments"), new RuntimeScalar("\n")).getList();
        }

        try {
            RuntimeScalar data = args.get(0);
            org.perlonjava.runtime.perlmodule.storable.StorableWriter w =
                    new org.perlonjava.runtime.perlmodule.storable.StorableWriter();
            w.setCanonical(GlobalVariable.getGlobalVariable("Storable::canonical").getBoolean());
            String encoded = w.writeTopLevelToMemory(data, netorder);
            // The encoded string holds bytes 0..255 as chars. Wrap as a
            // byte-string scalar so consumers see it as raw bytes (matches
            // the existing freeze() return shape).
            RuntimeScalar result = new RuntimeScalar(encoded);
            return result.getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("freeze failed: " + e.getMessage()), new RuntimeScalar("\n")).getList();
        }
    }

    /**
     * Thaws data frozen by {@link #freeze} / {@code nfreeze}. The
     * input is the in-memory native Perl Storable wire format
     * ({@code (major&lt;&lt;1) | netorder} flag byte followed by a
     * minor-version byte and the body). Earlier PerlOnJava builds
     * also accepted a legacy YAML+GZIP and an in-house 0xFF-magic
     * binary format on this entry point; those have been removed.
     */
    public static RuntimeList thaw(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return WarnDie.die(new RuntimeScalar("thaw: not enough arguments"), new RuntimeScalar("\n")).getList();
        }

        try {
            RuntimeScalar frozen = args.get(0);
            String frozenStr = frozen.toString();
            if (frozenStr.isEmpty()) {
                throw new IllegalArgumentException("Empty input");
            }
            byte[] bytes = new byte[frozenStr.length()];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) frozenStr.charAt(i);
            org.perlonjava.runtime.perlmodule.storable.StorableContext sCtx =
                    new org.perlonjava.runtime.perlmodule.storable.StorableContext(bytes);
            org.perlonjava.runtime.perlmodule.storable.Header.parseInMemory(sCtx);
            org.perlonjava.runtime.perlmodule.storable.StorableReader sReader =
                    new org.perlonjava.runtime.perlmodule.storable.StorableReader();
            RuntimeScalar data = sReader.dispatch(sCtx);
            // Drain the bare-container sentinel left by the
            // top-level container reader (if any) so it does not
            // leak into a subsequent thaw of unrelated data
            // sharing the same Storable runtime.
            sCtx.takeBareContainerFlag();
            if (!RuntimeScalarType.isReference(data)) {
                data = data.createReference();
            }
            return data.getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("thaw failed: " + e.getMessage()), new RuntimeScalar("\n")).getList();
        }
    }

    /**
     * Network freeze (same as freeze for now).
     */
    public static RuntimeList nfreeze(RuntimeArray args, int ctx) {
        return freezeImpl(args, true);
    }

    /**
     * Stores data to file using the native Perl Storable binary format
     * ({@code pst0} magic). For {@code store} the byte order is "native"
     * (we always emit big-endian-on-disk for round-trip determinism);
     * for {@code nstore} it is network order.
     */
    public static RuntimeList store(RuntimeArray args, int ctx) {
        return storeImpl(args, false);
    }

    private static RuntimeList storeImpl(RuntimeArray args, boolean netorder) {
        lastOpInNetorder = netorder;
        if (args.size() < 2) {
            return WarnDie.die(new RuntimeScalar("store: not enough arguments"), new RuntimeScalar("\n")).getList();
        }

        try {
            RuntimeScalar data = args.get(0);
            String filename = args.get(1).toString();

            org.perlonjava.runtime.perlmodule.storable.StorableWriter w =
                    new org.perlonjava.runtime.perlmodule.storable.StorableWriter();
            w.setCanonical(GlobalVariable.getGlobalVariable("Storable::canonical").getBoolean());
            String encoded = w.writeTopLevelToFile(data, netorder);
            // The encoded string holds bytes 0..255 as chars; convert back
            // to the raw byte sequence for file I/O.
            byte[] bytes = new byte[encoded.length()];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) encoded.charAt(i);
            Files.write(new File(filename).toPath(), bytes);

            return scalarTrue.getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("store failed: " + e.getMessage()), new RuntimeScalar("\n")).getList();
        }
    }

    /**
     * Retrieves data from file.
     */
    public static RuntimeList retrieve(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return WarnDie.die(new RuntimeScalar("retrieve: not enough arguments"), new RuntimeScalar("\n")).getList();
        }

        try {
            String filename = args.get(0).toString();
            byte[] raw = Files.readAllBytes(new File(filename).toPath());

            // Native Storable file format: "pst0" magic, then the same
            // header/body the in-memory format uses. The native reader
            // lives in {@code .storable.*}.
            if (raw.length < 4
                    || raw[0] != 'p' || raw[1] != 's' || raw[2] != 't' || raw[3] != '0') {
                throw new IllegalArgumentException(
                        "retrieve failed: " + filename + " is not a Storable file (no pst0 magic)");
            }
            org.perlonjava.runtime.perlmodule.storable.StorableContext sCtx =
                    new org.perlonjava.runtime.perlmodule.storable.StorableContext(raw);
            org.perlonjava.runtime.perlmodule.storable.Header.parseFile(sCtx);
            org.perlonjava.runtime.perlmodule.storable.StorableReader sReader =
                    new org.perlonjava.runtime.perlmodule.storable.StorableReader();
            RuntimeScalar data = sReader.dispatch(sCtx);
            // Drain the bare-container sentinel (see thaw).
            sCtx.takeBareContainerFlag();
            // Storable's `retrieve` always returns a reference (see
            // do_retrieve -> newRV_noinc in Storable.xs around L7601).
            // If the top-level opcode already produced a reference
            // (SX_REF / SX_ARRAY / SX_HASH / SX_BLESS yields one), return
            // it as-is. If it produced a bare scalar (SX_BYTE for nstore(\42)
            // collapses to bare SX_BYTE on disk), wrap it in a SCALARREFERENCE
            // so the caller can dereference uniformly.
            if (!RuntimeScalarType.isReference(data)) {
                data = data.createReference();
            }
            return data.getList();
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("retrieve failed:")) {
                return WarnDie.die(new RuntimeScalar(msg), new RuntimeScalar("\n")).getList();
            }
            return WarnDie.die(new RuntimeScalar("retrieve failed: " + msg), new RuntimeScalar("\n")).getList();
        }
    }

    /**
     * Network store (same as store).
     */
    public static RuntimeList nstore(RuntimeArray args, int ctx) {
        return storeImpl(args, true);
    }

    /**
     * Deep clone using direct deep-copy with STORABLE_freeze/thaw hook support.
     * <p>
     * When cloning a blessed object that has a STORABLE_freeze method, calls the
     * hook instead of traversing the object directly. This handles objects with
     * non-serializable internals (e.g., DBI handles with Java JDBC connections).
     */
    public static RuntimeList dclone(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return WarnDie.die(new RuntimeScalar("dclone: not enough arguments"), new RuntimeScalar("\n")).getList();
        }

        try {
            RuntimeScalar data = args.get(0);
            IdentityHashMap<Object, RuntimeScalar> cloned = new IdentityHashMap<>();
            RuntimeScalar result = deepClone(data, cloned);
            return result.getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("dclone failed: " + e.getMessage()), new RuntimeScalar("\n")).getList();
        }
    }

    /**
     * Phase G (refcount_alignment_52leaks_plan.md): release the
     * refCount bumps that {@link RuntimeArray#push} applied via
     * {@link RuntimeScalar#incrementRefCountForContainerStore} for
     * elements in an arg-passing array that's about to be discarded.
     * <p>
     * Storable's deepClone/freeze/thaw build temporary Java-side
     * {@link RuntimeArray} objects to hand to Perl-side hook
     * methods via {@link RuntimeCode#apply}. After the Perl call
     * returns, the Java array goes out of scope — but its elements'
     * {@code refCountOwned=true} flag keeps their referents'
     * refCount permanently inflated, which prevents downstream
     * cleanup (DESTROY, {@code clearWeakRefsTo}, and the 52leaks.t
     * {@code basic result_source_handle} assertion).
     * <p>
     * This helper decrements each element's referent refCount,
     * flips {@code refCountOwned=false}, and clears the list, so
     * subsequent JVM GC of the array is semantically aligned with
     * what a Perl-side {@code @_} release would do.
     *
     * @param args the temporary args array to release
     */
    /**
     * Best-effort attempt to load a class before blessing a retrieved object
     * into it. Without this, blessing into a not-yet-loaded class causes the
     * blessId to be allocated as "non-overloaded" (positive ID) — and once
     * cached, that ID stays positive forever, so even after the class is later
     * loaded with `use overload`, both the retrieved object AND every
     * subsequent {@code Class->new} for the same class will skip overload
     * dispatch (URI's stringification, comparison, etc. silently break).
     *
     * Failure to load is silently ignored: many recorded objects bless into
     * pure-data packages that have no .pm file, and that's fine — they just
     * don't have overload anyway.
     */
    private static void requireClassForBlessOnRetrieve(String className) {
        if (className == null || className.isEmpty()) return;
        if (className.equals("main") || className.equals("UNIVERSAL")) return;
        String filename = className.replace("::", "/").replace("'", "/") + ".pm";
        RuntimeHash inc = GlobalVariable.getGlobalHash("main::INC");
        if (inc.exists(new RuntimeScalar(filename)).getBoolean()) return;
        try {
            ModuleOperators.require(new RuntimeScalar(filename));
        } catch (Exception ignore) {
            // Class isn't a loadable module — fine, no overload to register.
        }
    }

    /**
     * Phase G (refcount_alignment_52leaks_plan.md): release the
     * refCount bumps that {@link RuntimeArray#push} applied via
     * {@link RuntimeScalar#incrementRefCountForContainerStore} for
     * elements in an arg-passing array that's about to be discarded.
     * <p>
     * Storable's deepClone/freeze/thaw build temporary Java-side
     * {@link RuntimeArray} objects to hand to Perl-side hook methods
     * via {@link RuntimeCode#apply}. After the Perl call returns,
     * the Java array goes out of scope — but its elements'
     * {@code refCountOwned=true} flag keeps their referents'
     * refCount permanently inflated, which prevents downstream
     * cleanup (DESTROY, {@code clearWeakRefsTo}, and the 52leaks.t
     * {@code basic result_source_handle} assertion).
     * <p>
     * Public so the encoder/decoder helpers in the {@code .storable}
     * subpackage can drain their hook callsites the same way the
     * dclone path does.
     */
    public static void releaseApplyArgs(RuntimeArray args) {
        if (args == null || args.elements == null) return;
        for (RuntimeScalar elem : args.elements) {
            if (elem == null) continue;
            if (elem.refCountOwned && elem.value instanceof RuntimeBase base
                    && base.refCount > 0) {
                base.releaseOwner(elem, "Storable.releaseApplyArgs");
                base.releaseActiveOwner(elem);
                base.refCount--;
                elem.refCountOwned = false;
            }
        }
        args.elements.clear();
        args.elementsOwned = false;
    }

    /**
     * Recursively deep-clones a RuntimeScalar, handling circular references and
     * STORABLE_freeze/STORABLE_thaw hooks on blessed objects.
     */
    private static RuntimeScalar deepClone(RuntimeScalar scalar, IdentityHashMap<Object, RuntimeScalar> cloned) {
        if (scalar == null) return new RuntimeScalar();

        // Check for already-cloned references (circular reference handling)
        if (scalar.value != null && cloned.containsKey(scalar.value)) {
            return cloned.get(scalar.value);
        }

        // Check for blessed objects with STORABLE_freeze hook
        int blessId = RuntimeScalarType.blessedId(scalar);
        if (blessId != 0) {
            String className = NameNormalizer.getBlessStr(blessId);
            RuntimeScalar freezeMethod = InheritanceResolver.findMethodInHierarchy(
                    "STORABLE_freeze", className, null, 0, false);

            if (freezeMethod != null && freezeMethod.type == RuntimeScalarType.CODE) {
                // Call STORABLE_freeze($self, $cloning=1)
                RuntimeArray freezeArgs = new RuntimeArray();
                RuntimeArray.push(freezeArgs, scalar);
                RuntimeArray.push(freezeArgs, new RuntimeScalar(1)); // cloning = true
                RuntimeList freezeResult = RuntimeCode.apply(freezeMethod, freezeArgs, RuntimeContextType.LIST);
                // Phase G (refcount_alignment_52leaks_plan.md): decrement
                // refCount bumps that RuntimeArray.push applied via
                // incrementRefCountForContainerStore. The args array is
                // a Java local vessel; its elements would otherwise keep
                // their referents' refCount permanently inflated,
                // preventing DESTROY / weak-ref clearing on objects that
                // had their only strong reference in this arg list
                // (DBIC's ResultSourceHandle via STORABLE_freeze).
                releaseApplyArgs(freezeArgs);
                RuntimeArray freezeArray = new RuntimeArray();
                freezeResult.setArrayOfAlias(freezeArray);

                // Per Perl 5 Storable: empty return from STORABLE_freeze cancels the
                // hook and falls through to default deep-copy
                if (freezeArray.size() > 0) {
                    // Create a new empty blessed object of the same reference type as the original
                    RuntimeScalar newObj;
                    if (scalar.type == RuntimeScalarType.ARRAYREFERENCE) {
                        newObj = new RuntimeArray().createAnonymousReference();
                    } else if (scalar.type == RuntimeScalarType.REFERENCE) {
                        newObj = new RuntimeScalar().createReference();
                    } else {
                        // Default to hash reference (most common case)
                        newObj = new RuntimeHash().createAnonymousReference();
                    }
                    ReferenceOperators.bless(newObj, new RuntimeScalar(className));
                    cloned.put(scalar.value, newObj);

                    // Call STORABLE_thaw($new_obj, $cloning=1, $serialized, @extra_refs)
                    RuntimeScalar thawMethod = InheritanceResolver.findMethodInHierarchy(
                            "STORABLE_thaw", className, null, 0, false);
                    if (thawMethod != null && thawMethod.type == RuntimeScalarType.CODE) {
                        RuntimeArray thawArgs = new RuntimeArray();
                        RuntimeArray.push(thawArgs, newObj);
                        RuntimeArray.push(thawArgs, new RuntimeScalar(1)); // cloning = true
                        // First element is the serialized string — pass as-is
                        RuntimeArray.push(thawArgs, freezeArray.get(0));
                        // Remaining elements are extra refs — deep-clone them
                        // so the thawed object gets independent copies
                        for (int i = 1; i < freezeArray.size(); i++) {
                            RuntimeArray.push(thawArgs, deepClone(freezeArray.get(i), cloned));
                        }
                        RuntimeCode.apply(thawMethod, thawArgs, RuntimeContextType.VOID);
                        // Phase G: release arg-push refCount bumps (see
                        // freezeArgs comment above).
                        releaseApplyArgs(thawArgs);
                    }

                    return newObj;
                }
                // Empty return — fall through to default deep-copy
            }
        }

        // Regular deep copy based on type
        return switch (scalar.type) {
            case RuntimeScalarType.HASHREFERENCE -> {
                RuntimeHash origHash = (RuntimeHash) scalar.value;
                RuntimeHash newHash = new RuntimeHash();
                // Anonymous ref: not bound to a named variable, so callDestroy
                // must fire when refCount reaches 0. Using createReference() here
                // would set localBindingExists=true and suppress DESTROY/weak-ref
                // clearing (DBIC t/52leaks.t test 18).
                RuntimeScalar newRef = newHash.createAnonymousReference();
                cloned.put(scalar.value, newRef);

                // Preserve blessing
                if (blessId != 0) {
                    String className = NameNormalizer.getBlessStr(blessId);
                    ReferenceOperators.bless(newRef, new RuntimeScalar(className));
                }

                // Check for tied hash — preserve tie magic
                if (origHash.type == RuntimeHash.TIED_HASH && origHash.elements instanceof TieHash tieHash) {
                    // Deep-clone the tie handler object
                    RuntimeScalar clonedSelf = deepClone(tieHash.getSelf(), cloned);
                    // Deep-clone the underlying data via FETCH iteration
                    RuntimeHash previousValue = new RuntimeHash();
                    // Create new TieHash with cloned handler
                    newHash.type = RuntimeHash.TIED_HASH;
                    newHash.elements = new TieHash(tieHash.getTiedPackage(), previousValue, clonedSelf);
                    // Copy the data through the tied interface (STORE calls)
                    // Iterate original hash via FIRSTKEY/NEXTKEY and FETCH each value
                    RuntimeScalar firstKey = TieHash.tiedFirstKey(origHash);
                    while (firstKey.type != RuntimeScalarType.UNDEF) {
                        RuntimeScalar val = TieHash.tiedFetch(origHash, firstKey);
                        TieHash.tiedStore(newHash, firstKey, deepClone(val, cloned));
                        firstKey = TieHash.tiedNextKey(origHash, firstKey);
                    }
                } else {
                    // Regular (untied) hash: deep-clone each value
                    origHash.elements.forEach((key, value) ->
                            newHash.put(key, deepClone(value, cloned)));
                }
                yield newRef;
            }
            case RuntimeScalarType.ARRAYREFERENCE -> {
                RuntimeArray origArray = (RuntimeArray) scalar.value;
                RuntimeArray newArray = new RuntimeArray();
                // Anonymous ref — see note on HASHREFERENCE case above.
                RuntimeScalar newRef = newArray.createAnonymousReference();
                cloned.put(scalar.value, newRef);

                // Preserve blessing
                if (blessId != 0) {
                    String className = NameNormalizer.getBlessStr(blessId);
                    ReferenceOperators.bless(newRef, new RuntimeScalar(className));
                }

                // Check for tied array — preserve tie magic
                if (origArray.type == RuntimeArray.TIED_ARRAY && origArray.elements instanceof TieArray tieArray) {
                    // Deep-clone the tie handler object
                    RuntimeScalar clonedSelf = deepClone(tieArray.getSelf(), cloned);
                    // Create new TieArray with cloned handler
                    RuntimeArray previousValue = new RuntimeArray();
                    newArray.type = RuntimeArray.TIED_ARRAY;
                    newArray.elements = new TieArray(tieArray.getTiedPackage(), previousValue, clonedSelf, newArray);
                    // Copy the data through the tied interface (STORE calls)
                    int size = TieArray.tiedFetchSize(origArray).getInt();
                    for (int i = 0; i < size; i++) {
                        RuntimeScalar val = TieArray.tiedFetch(origArray, new RuntimeScalar(i));
                        TieArray.tiedStore(newArray, new RuntimeScalar(i), deepClone(val, cloned));
                    }
                } else {
                    // Regular (untied) array: deep-clone each element
                    for (RuntimeScalar element : origArray.elements) {
                        newArray.elements.add(deepClone(element, cloned));
                    }
                }
                yield newRef;
            }
            case RuntimeScalarType.REFERENCE -> {
                // Scalar reference: clone the referenced value
                RuntimeScalar origValue = (RuntimeScalar) scalar.value;
                RuntimeScalar newValue = deepClone(origValue, cloned);
                RuntimeScalar newRef = newValue.createReference();
                cloned.put(scalar.value, newRef);

                // Preserve blessing
                if (blessId != 0) {
                    String className = NameNormalizer.getBlessStr(blessId);
                    ReferenceOperators.bless(newRef, new RuntimeScalar(className));
                }
                yield newRef;
            }
            case RuntimeScalarType.CODE -> {
                // CODE refs are shared, not cloned
                yield scalar;
            }
            case RuntimeScalarType.READONLY_SCALAR -> deepClone((RuntimeScalar) scalar.value, cloned);
            case RuntimeScalarType.TIED_SCALAR -> {
                // Tied scalar: deep-clone the handler and re-tie
                if (scalar.value instanceof TieScalar tieScalar) {
                    RuntimeScalar clonedSelf = deepClone(tieScalar.getSelf(), cloned);
                    // Fetch the current value through the tie to initialize the previous value
                    RuntimeScalar prevValue = new RuntimeScalar();
                    prevValue.set(tieScalar.tiedFetch());
                    // Create a new tied scalar with the cloned handler
                    RuntimeScalar copy = new RuntimeScalar();
                    copy.type = RuntimeScalarType.TIED_SCALAR;
                    copy.value = new TieScalar(tieScalar.getTiedPackage(), prevValue, clonedSelf);
                    yield copy;
                } else {
                    // Fallback: just copy the fetched value
                    RuntimeScalar copy = new RuntimeScalar();
                    copy.set(scalar);
                    yield copy;
                }
            }
            default -> {
                // Scalar values (int, double, string, undef) — just copy
                RuntimeScalar copy = new RuntimeScalar();
                copy.set(scalar);
                yield copy;
            }
        };
    }

}
