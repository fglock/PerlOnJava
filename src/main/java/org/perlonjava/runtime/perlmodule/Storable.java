package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ModuleOperators;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;
import org.perlonjava.runtime.mro.InheritanceResolver;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

/**
 * Storable module implementation using YAML with type tags for blessed objects.
 * <p>
 * This elegant approach leverages YAML's !! type tag system for object serialization:
 * - Uses !!perl/hash:ClassName for blessed objects
 * - Leverages YAML's built-in circular reference handling (anchors & aliases)
 * - Human readable format that's still debuggable
 * - Converts to binary only when needed for freeze()/nfreeze()
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
    private static final int SX_LSCALAR   = 1;   // Scalar (large) follows (length, data)
    private static final int SX_ARRAY     = 2;   // Array
    private static final int SX_HASH      = 3;   // Hash
    private static final int SX_REF       = 4;   // Reference to object
    private static final int SX_UNDEF     = 5;   // Undefined scalar
    private static final int SX_INTEGER   = 6;   // Integer
    private static final int SX_DOUBLE    = 7;   // Double
    private static final int SX_SCALAR    = 10;  // Scalar (small, length < 256)
    private static final int SX_SV_UNDEF  = 14;  // Perl's immortal PL_sv_undef
    private static final int SX_BLESS     = 17;  // Blessed object
    private static final int SX_OBJECT    = 0;   // Already stored (backreference)
    private static final int SX_HOOK      = 19;  // Storable hook (STORABLE_freeze/thaw)
    private static final int SX_CODE      = 26;  // Code reference

    // Magic byte to identify binary format (distinguishes from old YAML+GZIP format)
    private static final char BINARY_MAGIC = '\u00FF';

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
     * Freezes data to a binary format matching Perl 5 Storable's sort order.
     * Uses type bytes compatible with Perl 5's Storable so that string comparison
     * of frozen output produces the same ordering as Perl 5.
     */
    public static RuntimeList freeze(RuntimeArray args, int ctx) {
        lastOpInNetorder = false;
        if (args.isEmpty()) {
            return WarnDie.die(new RuntimeScalar("freeze: not enough arguments"), new RuntimeScalar("\n")).getList();
        }

        try {
            RuntimeScalar data = args.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append(BINARY_MAGIC);
            IdentityHashMap<Object, Integer> seen = new IdentityHashMap<>();
            serializeBinary(data, sb, seen);
            return new RuntimeScalar(sb.toString()).getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("freeze failed: " + e.getMessage()), new RuntimeScalar("\n")).getList();
        }
    }

    /**
     * Thaws frozen data back to objects. Handles both binary format and
     * legacy YAML+GZIP format for backward compatibility.
     */
    public static RuntimeList thaw(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return WarnDie.die(new RuntimeScalar("thaw: not enough arguments"), new RuntimeScalar("\n")).getList();
        }

        try {
            RuntimeScalar frozen = args.get(0);
            String frozenStr = frozen.toString();

            if (frozenStr.length() > 0 && frozenStr.charAt(0) == BINARY_MAGIC) {
                // New binary format
                int[] pos = {1}; // skip magic byte
                List<RuntimeScalar> refList = new ArrayList<>();
                RuntimeScalar data = deserializeBinary(frozenStr, pos, refList);
                return data.getList();
            } else {
                // Legacy YAML+GZIP format (strip old type prefix if present)
                if (frozenStr.length() > 0 && frozenStr.charAt(0) < '\u0010') {
                    frozenStr = frozenStr.substring(1);
                }
                String yaml = decompressString(frozenStr);
                RuntimeScalar data = deserializeFromYAML(yaml);
                return data.getList();
            }
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("thaw failed: " + e.getMessage()), new RuntimeScalar("\n")).getList();
        }
    }

    /**
     * Recursively serializes a RuntimeScalar to binary format with Storable-compatible
     * type bytes. Hash keys are sorted (canonical mode) for deterministic output.
     */
    private static void serializeBinary(RuntimeScalar scalar, StringBuilder sb, IdentityHashMap<Object, Integer> seen) {
        if (scalar == null || scalar.type == RuntimeScalarType.UNDEF) {
            sb.append((char) SX_SV_UNDEF);
            return;
        }

        // Circular reference detection
        if (scalar.value != null && seen.containsKey(scalar.value)) {
            sb.append((char) SX_OBJECT);
            appendInt(sb, seen.get(scalar.value));
            return;
        }

        // Blessed objects: check for STORABLE_freeze hook first
        int blessId = RuntimeScalarType.blessedId(scalar);
        if (blessId != 0) {
            String className = NameNormalizer.getBlessStr(blessId);

            // Check for STORABLE_freeze hook
            RuntimeScalar freezeMethod = InheritanceResolver.findMethodInHierarchy(
                    "STORABLE_freeze", className, null, 0, false);

            if (freezeMethod != null && freezeMethod.type == RuntimeScalarType.CODE) {
                // Call STORABLE_freeze($self, $cloning=0)
                RuntimeArray freezeArgs = new RuntimeArray();
                RuntimeArray.push(freezeArgs, scalar);
                RuntimeArray.push(freezeArgs, new RuntimeScalar(0)); // cloning = false
                RuntimeList freezeResult = RuntimeCode.apply(freezeMethod, freezeArgs, RuntimeContextType.LIST);
                // Phase G: release arg-push refCount bumps — see releaseApplyArgs Javadoc.
                releaseApplyArgs(freezeArgs);
                RuntimeArray freezeArray = new RuntimeArray();
                freezeResult.setArrayOfAlias(freezeArray);

                // Per Perl 5 Storable: empty return from STORABLE_freeze cancels the
                // hook and falls through to default serialization (SX_BLESS path)
                if (freezeArray.size() > 0) {
                    // Track for circular reference detection before emitting
                    if (scalar.value != null) seen.put(scalar.value, seen.size());

                    // Emit SX_HOOK + class name + ref-type byte + serialized string + extra refs
                    // The ref-type byte tells SX_HOOK reader what kind of empty
                    // reference to create before passing to STORABLE_thaw
                    // (required because hooks like URI's bless a SCALAR ref —
                    // creating a HASH ref would make `$$self = $str` croak).
                    sb.append((char) SX_HOOK);
                    appendInt(sb, className.length());
                    sb.append(className);

                    // Encode the original reference type so SX_HOOK reader can
                    // recreate the same kind of reference.
                    char refTypeByte;
                    if (scalar.type == RuntimeScalarType.ARRAYREFERENCE) {
                        refTypeByte = 'A';
                    } else if (scalar.type == RuntimeScalarType.REFERENCE) {
                        refTypeByte = 'S';
                    } else {
                        refTypeByte = 'H'; // hash ref (default)
                    }
                    sb.append(refTypeByte);

                    // Serialized string (first element of freeze result)
                    String serialized = freezeArray.get(0).toString();
                    appendInt(sb, serialized.length());
                    sb.append(serialized);

                    // Extra refs (remaining elements)
                    int extraRefs = freezeArray.size() - 1;
                    appendInt(sb, extraRefs);
                    for (int i = 1; i <= extraRefs; i++) {
                        serializeBinary(freezeArray.get(i), sb, seen);
                    }
                    return;
                }
                // Empty return — fall through to default SX_BLESS serialization
            }

            // No hook — emit SX_BLESS + class name before the data
            sb.append((char) SX_BLESS);
            appendInt(sb, className.length());
            sb.append(className);
        }

        switch (scalar.type) {
            case RuntimeScalarType.HASHREFERENCE -> {
                RuntimeHash hash = (RuntimeHash) scalar.value;
                if (hash != null) seen.put(scalar.value, seen.size());
                sb.append((char) SX_HASH);
                int size = (hash != null) ? hash.size() : 0;
                appendInt(sb, size);
                if (hash != null) {
                    // Canonical mode: sort keys for deterministic output
                    // Perl 5's Storable writes VALUE first, then KEY (critical for sort order)
                    TreeMap<String, RuntimeScalar> sorted = new TreeMap<>(hash.elements);
                    for (Map.Entry<String, RuntimeScalar> entry : sorted.entrySet()) {
                        serializeBinary(entry.getValue(), sb, seen);
                        String key = entry.getKey();
                        appendInt(sb, key.length());
                        sb.append(key);
                    }
                }
            }
            case RuntimeScalarType.ARRAYREFERENCE -> {
                RuntimeArray array = (RuntimeArray) scalar.value;
                if (array != null) seen.put(scalar.value, seen.size());
                sb.append((char) SX_ARRAY);
                int size = (array != null) ? array.size() : 0;
                appendInt(sb, size);
                if (array != null) {
                    for (RuntimeScalar element : array.elements) {
                        serializeBinary(element, sb, seen);
                    }
                }
            }
            case RuntimeScalarType.REFERENCE -> {
                if (scalar.value != null) seen.put(scalar.value, seen.size());
                sb.append((char) SX_REF);
                serializeBinary((RuntimeScalar) scalar.value, sb, seen);
            }
            case RuntimeScalarType.INTEGER -> {
                sb.append((char) SX_INTEGER);
                appendLong(sb, scalar.getLong());
            }
            case RuntimeScalarType.DOUBLE -> {
                sb.append((char) SX_DOUBLE);
                appendLong(sb, Double.doubleToLongBits(scalar.getDouble()));
            }
            case RuntimeScalarType.CODE -> {
                sb.append((char) SX_CODE);
            }
            case RuntimeScalarType.READONLY_SCALAR -> {
                serializeBinary((RuntimeScalar) scalar.value, sb, seen);
            }
            default -> {
                // String types (STRING, BYTE_STRING, VSTRING, etc.)
                if (scalar.value == null) {
                    sb.append((char) SX_SV_UNDEF);
                } else {
                    String str = scalar.toString();
                    if (str.length() < 256) {
                        sb.append((char) SX_SCALAR);
                        sb.append((char) str.length());
                        sb.append(str);
                    } else {
                        sb.append((char) SX_LSCALAR);
                        appendInt(sb, str.length());
                        sb.append(str);
                    }
                }
            }
        }
    }

    /**
     * Deserializes binary data back to a RuntimeScalar.
     */
    private static RuntimeScalar deserializeBinary(String data, int[] pos, List<RuntimeScalar> refList) {
        if (pos[0] >= data.length()) return new RuntimeScalar();

        int type = data.charAt(pos[0]++) & 0xFF;

        // Handle blessed prefix
        String blessClass = null;
        if (type == SX_BLESS) {
            int classLen = readInt(data, pos);
            blessClass = data.substring(pos[0], pos[0] + classLen);
            pos[0] += classLen;
            type = data.charAt(pos[0]++) & 0xFF;
        }

        RuntimeScalar result;
        switch (type) {
            case SX_OBJECT -> {
                int refIdx = readInt(data, pos);
                return refList.get(refIdx);
            }
            case SX_HOOK -> {
                // Object with STORABLE_freeze/thaw hooks
                int classLen = readInt(data, pos);
                String hookClass = data.substring(pos[0], pos[0] + classLen);
                pos[0] += classLen;

                // Reference type byte (matches what serializeBinary emitted):
                // 'A'=array, 'S'=scalar, 'H'=hash. Created in 2026 to fix
                // STORABLE_thaw on scalar-ref-blessed classes like URI.
                char refTypeByte = data.charAt(pos[0]++);

                // Read serialized string
                int serLen = readInt(data, pos);
                String serialized = data.substring(pos[0], pos[0] + serLen);
                pos[0] += serLen;

                // Read extra refs
                int extraRefCount = readInt(data, pos);
                List<RuntimeScalar> extraRefs = new ArrayList<>();
                for (int i = 0; i < extraRefCount; i++) {
                    extraRefs.add(deserializeBinary(data, pos, refList));
                }

                // Create new blessed object of the same reference type as the
                // original. URI etc. expect a scalar ref, others expect a hash
                // or array ref.
                if (refTypeByte == 'A') {
                    result = new RuntimeArray().createAnonymousReference();
                } else if (refTypeByte == 'S') {
                    result = new RuntimeScalar().createReference();
                } else {
                    RuntimeHash newHash = new RuntimeHash();
                    result = newHash.createAnonymousReference();
                }
                requireClassForBlessOnRetrieve(hookClass);
                ReferenceOperators.bless(result, new RuntimeScalar(hookClass));
                refList.add(result);

                // Call STORABLE_thaw($new_obj, $cloning=0, $serialized, @extra_refs)
                RuntimeScalar thawMethod = InheritanceResolver.findMethodInHierarchy(
                        "STORABLE_thaw", hookClass, null, 0, false);
                if (thawMethod != null && thawMethod.type == RuntimeScalarType.CODE) {
                    RuntimeArray thawArgs = new RuntimeArray();
                    RuntimeArray.push(thawArgs, result);
                    RuntimeArray.push(thawArgs, new RuntimeScalar(0)); // cloning = false
                    RuntimeArray.push(thawArgs, new RuntimeScalar(serialized));
                    for (RuntimeScalar ref : extraRefs) {
                        RuntimeArray.push(thawArgs, ref);
                    }
                    RuntimeCode.apply(thawMethod, thawArgs, RuntimeContextType.VOID);
                    // Phase G: release arg-push refCount bumps.
                    releaseApplyArgs(thawArgs);
                }
            }
            case SX_HASH -> {
                RuntimeHash hash = new RuntimeHash();
                result = hash.createAnonymousReference();
                refList.add(result);
                int numKeys = readInt(data, pos);
                for (int i = 0; i < numKeys; i++) {
                    // Perl 5's Storable format: VALUE first, then KEY
                    RuntimeScalar value = deserializeBinary(data, pos, refList);
                    int keyLen = readInt(data, pos);
                    String key = data.substring(pos[0], pos[0] + keyLen);
                    pos[0] += keyLen;
                    hash.put(key, value);
                }
            }
            case SX_ARRAY -> {
                RuntimeArray array = new RuntimeArray();
                result = array.createAnonymousReference();
                refList.add(result);
                int numElements = readInt(data, pos);
                for (int i = 0; i < numElements; i++) {
                    array.elements.add(deserializeBinary(data, pos, refList));
                }
            }
            case SX_REF -> {
                RuntimeScalar value = deserializeBinary(data, pos, refList);
                result = value.createReference();
                refList.add(result);
            }
            case SX_INTEGER -> {
                result = new RuntimeScalar(readLong(data, pos));
            }
            case SX_DOUBLE -> {
                result = new RuntimeScalar(Double.longBitsToDouble(readLong(data, pos)));
            }
            case SX_SCALAR -> {
                int len = data.charAt(pos[0]++) & 0xFF;
                result = new RuntimeScalar(data.substring(pos[0], pos[0] + len));
                pos[0] += len;
            }
            case SX_LSCALAR -> {
                int len = readInt(data, pos);
                result = new RuntimeScalar(data.substring(pos[0], pos[0] + len));
                pos[0] += len;
            }
            case SX_SV_UNDEF, SX_UNDEF -> {
                result = new RuntimeScalar();
            }
            default -> {
                result = new RuntimeScalar();
            }
        }

        if (blessClass != null) {
            requireClassForBlessOnRetrieve(blessClass);
            ReferenceOperators.bless(result, new RuntimeScalar(blessClass));
        }
        return result;
    }

    /** Appends a 4-byte big-endian int to the buffer. */
    private static void appendInt(StringBuilder sb, int value) {
        sb.append((char) ((value >> 24) & 0xFF));
        sb.append((char) ((value >> 16) & 0xFF));
        sb.append((char) ((value >> 8) & 0xFF));
        sb.append((char) (value & 0xFF));
    }

    /** Appends an 8-byte big-endian long to the buffer. */
    private static void appendLong(StringBuilder sb, long value) {
        for (int i = 56; i >= 0; i -= 8) {
            sb.append((char) ((value >> i) & 0xFF));
        }
    }

    /** Reads a 4-byte big-endian int from the data. */
    private static int readInt(String data, int[] pos) {
        int value = ((data.charAt(pos[0]) & 0xFF) << 24)
                  | ((data.charAt(pos[0] + 1) & 0xFF) << 16)
                  | ((data.charAt(pos[0] + 2) & 0xFF) << 8)
                  | (data.charAt(pos[0] + 3) & 0xFF);
        pos[0] += 4;
        return value;
    }

    /** Reads an 8-byte big-endian long from the data. */
    private static long readLong(String data, int[] pos) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data.charAt(pos[0]++) & 0xFF);
        }
        return value;
    }

    /**
     * Network freeze (same as freeze for now).
     */
    public static RuntimeList nfreeze(RuntimeArray args, int ctx) {
        RuntimeList result = freeze(args, ctx);
        lastOpInNetorder = true;
        return result;
    }

    /**
     * Stores data to file using YAML format.
     */
    public static RuntimeList store(RuntimeArray args, int ctx) {
        lastOpInNetorder = false;
        if (args.size() < 2) {
            return WarnDie.die(new RuntimeScalar("store: not enough arguments"), new RuntimeScalar("\n")).getList();
        }

        try {
            RuntimeScalar data = args.get(0);
            String filename = args.get(1).toString();

            String yaml = serializeToYAML(data);
            Files.write(new File(filename).toPath(), yaml.getBytes(StandardCharsets.UTF_8));

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

            // Detect native-format Storable files by their "pst0" magic.
            // These are written by upstream Perl (and now by jperl on the
            // round-trip path). Read them with the native binary reader
            // built in src/main/java/.../perlmodule/storable/.
            if (raw.length >= 4
                    && raw[0] == 'p' && raw[1] == 's' && raw[2] == 't' && raw[3] == '0') {
                org.perlonjava.runtime.perlmodule.storable.StorableContext sCtx =
                        new org.perlonjava.runtime.perlmodule.storable.StorableContext(raw);
                org.perlonjava.runtime.perlmodule.storable.Header.parseFile(sCtx);
                org.perlonjava.runtime.perlmodule.storable.StorableReader sReader =
                        new org.perlonjava.runtime.perlmodule.storable.StorableReader();
                RuntimeScalar data = sReader.dispatch(sCtx);
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
            }

            String yaml = new String(raw, StandardCharsets.UTF_8);
            RuntimeScalar data = deserializeFromYAML(yaml);
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
        RuntimeList result = store(args, ctx);
        lastOpInNetorder = true;
        return result;
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

    private static void releaseApplyArgs(RuntimeArray args) {
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

    /**
     * Serializes RuntimeScalar to YAML with type tags for blessed objects.
     */
    private static String serializeToYAML(RuntimeScalar data) {
        DumpSettings settings = DumpSettings.builder()
                .setDefaultFlowStyle(FlowStyle.BLOCK)
                .setSchema(new CoreSchema())
                .build();

        Dump dump = new Dump(settings);
        IdentityHashMap<Object, Object> seen = new IdentityHashMap<>();
        Object yamlObject = convertToYAMLWithTags(data, seen);
        return dump.dumpToString(yamlObject);
    }

    /**
     * Deserializes YAML back to RuntimeScalar, handling type tags.
     */
    private static RuntimeScalar deserializeFromYAML(String yaml) {
        LoadSettings settings = LoadSettings.builder()
                .setSchema(new CoreSchema())
                .setCodePointLimit(50 * 1024 * 1024)  // 50MB limit for large CPAN metadata files
                .build();

        Load load = new Load(settings);
        Object yamlObject = load.loadFromString(yaml);
        IdentityHashMap<Object, RuntimeScalar> seen = new IdentityHashMap<>();
        return convertFromYAMLWithTags(yamlObject, seen);
    }

    /**
     * Converts RuntimeScalar to YAML object with type tags for blessed objects.
     * Supports STORABLE_freeze hooks on blessed objects.
     */
    @SuppressWarnings("unchecked")
    private static Object convertToYAMLWithTags(RuntimeScalar scalar, IdentityHashMap<Object, Object> seen) {
        if (scalar == null) return null;

        if (scalar.value != null && seen.containsKey(scalar.value)) {
            return seen.get(scalar.value);
        }

        // Check if blessed object
        int blessId = RuntimeScalarType.blessedId(scalar);
        if (blessId != 0) {
            String className = NameNormalizer.getBlessStr(blessId);

            // Check for STORABLE_freeze hook
            RuntimeScalar freezeMethod = InheritanceResolver.findMethodInHierarchy(
                    "STORABLE_freeze", className, null, 0, false);
            if (freezeMethod != null && freezeMethod.type == RuntimeScalarType.CODE) {
                // Call STORABLE_freeze($self, $cloning=0) for serialization
                RuntimeArray freezeArgs = new RuntimeArray();
                RuntimeArray.push(freezeArgs, scalar);
                RuntimeArray.push(freezeArgs, new RuntimeScalar(0)); // cloning = false
                RuntimeList freezeResult = RuntimeCode.apply(freezeMethod, freezeArgs, RuntimeContextType.LIST);
                // Phase G: release arg-push refCount bumps.
                releaseApplyArgs(freezeArgs);
                RuntimeArray freezeArray = new RuntimeArray();
                freezeResult.setArrayOfAlias(freezeArray);

                // Per Perl 5 Storable: empty return from STORABLE_freeze cancels the
                // hook and falls through to default !!perl/hash: serialization
                if (freezeArray.size() > 0) {
                    // Store serialized data with class tag.
                    // The tag encodes the original reference type so the
                    // reader can recreate a reference of the right kind
                    // before calling STORABLE_thaw — required for hooks like
                    // URI's that expect a scalar ref ($$self = $str).
                    String tagPrefix;
                    if (scalar.type == RuntimeScalarType.ARRAYREFERENCE) {
                        tagPrefix = "!!perl/freezeA:";
                    } else if (scalar.type == RuntimeScalarType.REFERENCE) {
                        tagPrefix = "!!perl/freezeS:";
                    } else {
                        tagPrefix = "!!perl/freeze:"; // hash ref (also legacy)
                    }
                    Map<String, Object> taggedObject = new LinkedHashMap<>();
                    // STORABLE_freeze returns (serialized_string, @extra_refs)
                    // Store the serialized string directly
                    taggedObject.put(tagPrefix + className, freezeArray.get(0).toString());
                    return taggedObject;
                }
                // Empty return — fall through to default !!perl/hash: serialization
            }

            Map<String, Object> taggedObject = new LinkedHashMap<>();
            taggedObject.put("!!perl/hash:" + className, convertScalarValue(scalar, seen));
            return taggedObject;
        }

        return convertScalarValue(scalar, seen);
    }

    private static Object convertScalarValue(RuntimeScalar scalar, IdentityHashMap<Object, Object> seen) {
        return switch (scalar.type) {
            case RuntimeScalarType.REFERENCE -> {
                // Handle scalar references like \$x
                Map<String, Object> refMap = new LinkedHashMap<>();
                refMap.put("!!perl/ref", convertToYAMLWithTags((RuntimeScalar) scalar.value, seen));
                yield refMap;
            }
            case RuntimeScalarType.HASHREFERENCE -> {
                Map<String, Object> map = new LinkedHashMap<>();
                seen.put(scalar.value, map);
                RuntimeHash hash = (RuntimeHash) scalar.value;
                hash.elements.forEach((key, value) ->
                        map.put(key, convertToYAMLWithTags(value, seen)));
                yield map;
            }
            case RuntimeScalarType.ARRAYREFERENCE -> {
                List<Object> list = new ArrayList<>();
                seen.put(scalar.value, list);
                RuntimeArray array = (RuntimeArray) scalar.value;
                array.elements.forEach(element -> {
                    if (element instanceof RuntimeScalar elementScalar) {
                        list.add(convertToYAMLWithTags(elementScalar, seen));
                    }
                });
                yield list;
            }
            case RuntimeScalarType.STRING, RuntimeScalarType.BYTE_STRING, RuntimeScalarType.VSTRING -> {
                if (scalar.value == null) {
                    // Handle undef values with special tag
                    Map<String, Object> undefMap = new LinkedHashMap<>();
                    undefMap.put("!!perl/undef", null);
                    yield undefMap;
                } else {
                    yield scalar.toString();
                }
            }
            case RuntimeScalarType.DOUBLE -> scalar.getDouble();
            case RuntimeScalarType.INTEGER -> scalar.getLong();
            case RuntimeScalarType.BOOLEAN -> scalar.getBoolean();
            case RuntimeScalarType.READONLY_SCALAR -> convertScalarValue((RuntimeScalar) scalar.value, seen);
            case RuntimeScalarType.UNDEF -> {
                // Handle undef values with special tag
                Map<String, Object> undefMap = new LinkedHashMap<>();
                undefMap.put("!!perl/undef", null);
                yield undefMap;
            }
            default -> {
                if (scalar.value == null) {
                    // Handle undef values with special tag
                    Map<String, Object> undefMap = new LinkedHashMap<>();
                    undefMap.put("!!perl/undef", null);
                    yield undefMap;
                } else {
                    yield scalar.toString();
                }
            }
        };
    }

    /**
     * Converts YAML object back to RuntimeScalar, handling type tags.
     */
    @SuppressWarnings("unchecked")
    private static RuntimeScalar convertFromYAMLWithTags(Object yaml, IdentityHashMap<Object, RuntimeScalar> seen) {
        if (yaml == null) return new RuntimeScalar();

        if (seen.containsKey(yaml)) {
            return seen.get(yaml);
        }

        return switch (yaml) {
            case Map<?, ?> map -> {
                // Check for type tags
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = entry.getKey().toString();
                    if (key.startsWith("!!perl/hash:")) {
                        String className = key.substring("!!perl/hash:".length());
                        RuntimeScalar obj = convertFromYAMLWithTags(entry.getValue(), seen);
                        if (RuntimeScalarType.isReference(obj)) {
                            requireClassForBlessOnRetrieve(className);
                            ReferenceOperators.bless(obj, new RuntimeScalar(className));
                        }
                        yield obj;
                    } else if (key.startsWith("!!perl/freeze:") || key.startsWith("!!perl/freezeS:") || key.startsWith("!!perl/freezeA:")) {
                        // Handle STORABLE_freeze/thaw hooks. Tag encodes the
                        // original reference type so we can build a value the
                        // hook's STORABLE_thaw expects (URI's hook does
                        // `$$self = $str`, so $self must be a scalar ref).
                        String className;
                        RuntimeScalar newObj;
                        if (key.startsWith("!!perl/freezeS:")) {
                            className = key.substring("!!perl/freezeS:".length());
                            newObj = new RuntimeScalar().createReference();
                        } else if (key.startsWith("!!perl/freezeA:")) {
                            className = key.substring("!!perl/freezeA:".length());
                            newObj = new RuntimeArray().createAnonymousReference();
                        } else {
                            className = key.substring("!!perl/freeze:".length());
                            newObj = new RuntimeHash().createAnonymousReference();
                        }
                        requireClassForBlessOnRetrieve(className);
                        ReferenceOperators.bless(newObj, new RuntimeScalar(className));

                        // Call STORABLE_thaw($new_obj, $cloning=0, $serialized_string)
                        RuntimeScalar thawMethod = InheritanceResolver.findMethodInHierarchy(
                                "STORABLE_thaw", className, null, 0, false);
                        if (thawMethod != null && thawMethod.type == RuntimeScalarType.CODE) {
                            RuntimeArray thawArgs = new RuntimeArray();
                            RuntimeArray.push(thawArgs, newObj);
                            RuntimeArray.push(thawArgs, new RuntimeScalar(0)); // cloning = false
                            RuntimeArray.push(thawArgs, new RuntimeScalar(
                                    entry.getValue() != null ? entry.getValue().toString() : ""));
                            RuntimeCode.apply(thawMethod, thawArgs, RuntimeContextType.VOID);
                            // Phase G: release arg-push refCount bumps.
                            releaseApplyArgs(thawArgs);
                        }
                        yield newObj;
                    } else if (key.equals("!!perl/ref")) {
                        // Handle scalar references like \$x
                        RuntimeScalar referenced = convertFromYAMLWithTags(entry.getValue(), seen);
                        yield referenced.createReference();
                    } else if (key.equals("!!perl/undef")) {
                        // Handle undef values
                        yield new RuntimeScalar();
                    }
                }

                // Regular hash
                RuntimeHash hash = new RuntimeHash();
                RuntimeScalar hashRef = hash.createAnonymousReference();
                seen.put(yaml, hashRef);
                map.forEach((key, value) ->
                        hash.put(key.toString(), convertFromYAMLWithTags(value, seen)));
                yield hashRef;
            }
            case List<?> list -> {
                RuntimeArray array = new RuntimeArray();
                RuntimeScalar arrayRef = array.createAnonymousReference();
                seen.put(yaml, arrayRef);
                list.forEach(item ->
                        array.elements.add(convertFromYAMLWithTags(item, seen)));
                yield arrayRef;
            }
            case String s -> new RuntimeScalar(s);
            case Integer i -> new RuntimeScalar(i);
            case Long l -> new RuntimeScalar(l);
            case Double d -> new RuntimeScalar(d);
            case Boolean b -> new RuntimeScalar(b);
            default -> new RuntimeScalar(yaml.toString());
        };
    }

    private static String compressString(String input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(input.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static String decompressString(String compressed) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(compressed);
        try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(gzis.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
