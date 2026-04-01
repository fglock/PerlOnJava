package org.perlonjava.runtime.perlmodule;

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

            storable.defineExport("EXPORT", "store", "retrieve", "nstore", "freeze", "thaw", "nfreeze", "dclone");

        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Storable method: " + e.getMessage());
        }
    }

    /**
     * Freezes data to binary using YAML + compression.
     */
    public static RuntimeList freeze(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return WarnDie.die(new RuntimeScalar("freeze: not enough arguments"), new RuntimeScalar("\n")).getList();
        }

        try {
            RuntimeScalar data = args.get(0);
            String yaml = serializeToYAML(data);
            String compressed = compressString(yaml);
            return new RuntimeScalar(compressed).getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("freeze failed: " + e.getMessage()), new RuntimeScalar("\n")).getList();
        }
    }

    /**
     * Thaws binary data back to objects.
     */
    public static RuntimeList thaw(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return WarnDie.die(new RuntimeScalar("thaw: not enough arguments"), new RuntimeScalar("\n")).getList();
        }

        try {
            RuntimeScalar frozen = args.get(0);
            String yaml = decompressString(frozen.toString());
            RuntimeScalar data = deserializeFromYAML(yaml);
            return data.getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("thaw failed: " + e.getMessage()), new RuntimeScalar("\n")).getList();
        }
    }

    /**
     * Network freeze (same as freeze for now).
     */
    public static RuntimeList nfreeze(RuntimeArray args, int ctx) {
        return freeze(args, ctx);
    }

    /**
     * Stores data to file using YAML format.
     */
    public static RuntimeList store(RuntimeArray args, int ctx) {
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
            String yaml = new String(Files.readAllBytes(new File(filename).toPath()), StandardCharsets.UTF_8);

            RuntimeScalar data = deserializeFromYAML(yaml);
            return data.getList();
        } catch (Exception e) {
            return WarnDie.die(new RuntimeScalar("retrieve failed: " + e.getMessage()), new RuntimeScalar("\n")).getList();
        }
    }

    /**
     * Network store (same as store).
     */
    public static RuntimeList nstore(RuntimeArray args, int ctx) {
        return store(args, ctx);
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
                    "STORABLE_freeze", className, null, 0);

            if (freezeMethod != null && freezeMethod.type == RuntimeScalarType.CODE) {
                // Call STORABLE_freeze($self, $cloning=1)
                RuntimeArray freezeArgs = new RuntimeArray();
                RuntimeArray.push(freezeArgs, scalar);
                RuntimeArray.push(freezeArgs, new RuntimeScalar(1)); // cloning = true
                RuntimeList freezeResult = RuntimeCode.apply(freezeMethod, freezeArgs, RuntimeContextType.LIST);
                RuntimeArray freezeArray = new RuntimeArray();
                freezeResult.setArrayOfAlias(freezeArray);

                // Create a new empty blessed object of the same class
                RuntimeHash newHash = new RuntimeHash();
                RuntimeScalar newObj = newHash.createReference();
                ReferenceOperators.bless(newObj, new RuntimeScalar(className));
                cloned.put(scalar.value, newObj);

                // Call STORABLE_thaw($new_obj, $cloning=1, $serialized, @extra_refs)
                RuntimeScalar thawMethod = InheritanceResolver.findMethodInHierarchy(
                        "STORABLE_thaw", className, null, 0);
                if (thawMethod != null && thawMethod.type == RuntimeScalarType.CODE) {
                    RuntimeArray thawArgs = new RuntimeArray();
                    RuntimeArray.push(thawArgs, newObj);
                    RuntimeArray.push(thawArgs, new RuntimeScalar(1)); // cloning = true
                    // Pass serialized data and any extra refs from freeze
                    for (int i = 0; i < freezeArray.size(); i++) {
                        RuntimeArray.push(thawArgs, freezeArray.get(i));
                    }
                    RuntimeCode.apply(thawMethod, thawArgs, RuntimeContextType.VOID);
                }

                return newObj;
            }
        }

        // Regular deep copy based on type
        return switch (scalar.type) {
            case RuntimeScalarType.HASHREFERENCE -> {
                RuntimeHash origHash = (RuntimeHash) scalar.value;
                RuntimeHash newHash = new RuntimeHash();
                RuntimeScalar newRef = newHash.createReference();
                cloned.put(scalar.value, newRef);

                // Preserve blessing
                if (blessId != 0) {
                    String className = NameNormalizer.getBlessStr(blessId);
                    ReferenceOperators.bless(newRef, new RuntimeScalar(className));
                }

                // Deep-clone each value
                origHash.elements.forEach((key, value) ->
                        newHash.put(key, deepClone(value, cloned)));
                yield newRef;
            }
            case RuntimeScalarType.ARRAYREFERENCE -> {
                RuntimeArray origArray = (RuntimeArray) scalar.value;
                RuntimeArray newArray = new RuntimeArray();
                RuntimeScalar newRef = newArray.createReference();
                cloned.put(scalar.value, newRef);

                // Preserve blessing
                if (blessId != 0) {
                    String className = NameNormalizer.getBlessStr(blessId);
                    ReferenceOperators.bless(newRef, new RuntimeScalar(className));
                }

                // Deep-clone each element
                for (RuntimeScalar element : origArray.elements) {
                    newArray.elements.add(deepClone(element, cloned));
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
                    "STORABLE_freeze", className, null, 0);
            if (freezeMethod != null && freezeMethod.type == RuntimeScalarType.CODE) {
                // Call STORABLE_freeze($self, $cloning=0) for serialization
                RuntimeArray freezeArgs = new RuntimeArray();
                RuntimeArray.push(freezeArgs, scalar);
                RuntimeArray.push(freezeArgs, new RuntimeScalar(0)); // cloning = false
                RuntimeList freezeResult = RuntimeCode.apply(freezeMethod, freezeArgs, RuntimeContextType.LIST);
                RuntimeArray freezeArray = new RuntimeArray();
                freezeResult.setArrayOfAlias(freezeArray);

                // Store serialized data with class tag
                Map<String, Object> taggedObject = new LinkedHashMap<>();
                if (freezeArray.size() > 0) {
                    // STORABLE_freeze returns (serialized_string, @extra_refs)
                    // Store the serialized string directly
                    taggedObject.put("!!perl/freeze:" + className, freezeArray.get(0).toString());
                } else {
                    taggedObject.put("!!perl/freeze:" + className, "");
                }
                return taggedObject;
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
                            ReferenceOperators.bless(obj, new RuntimeScalar(className));
                        }
                        yield obj;
                    } else if (key.startsWith("!!perl/freeze:")) {
                        // Handle STORABLE_freeze/thaw hooks
                        String className = key.substring("!!perl/freeze:".length());
                        RuntimeHash newHash = new RuntimeHash();
                        RuntimeScalar newObj = newHash.createReference();
                        ReferenceOperators.bless(newObj, new RuntimeScalar(className));

                        // Call STORABLE_thaw($new_obj, $cloning=0, $serialized_string)
                        RuntimeScalar thawMethod = InheritanceResolver.findMethodInHierarchy(
                                "STORABLE_thaw", className, null, 0);
                        if (thawMethod != null && thawMethod.type == RuntimeScalarType.CODE) {
                            RuntimeArray thawArgs = new RuntimeArray();
                            RuntimeArray.push(thawArgs, newObj);
                            RuntimeArray.push(thawArgs, new RuntimeScalar(0)); // cloning = false
                            RuntimeArray.push(thawArgs, new RuntimeScalar(
                                    entry.getValue() != null ? entry.getValue().toString() : ""));
                            RuntimeCode.apply(thawMethod, thawArgs, RuntimeContextType.VOID);
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
                RuntimeScalar hashRef = hash.createReference();
                seen.put(yaml, hashRef);
                map.forEach((key, value) ->
                        hash.put(key.toString(), convertFromYAMLWithTags(value, seen)));
                yield hashRef;
            }
            case List<?> list -> {
                RuntimeArray array = new RuntimeArray();
                RuntimeScalar arrayRef = array.createReference();
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
