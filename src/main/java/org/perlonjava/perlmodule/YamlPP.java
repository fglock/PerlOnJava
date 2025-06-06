package org.perlonjava.perlmodule;

import org.perlonjava.operators.ReferenceOperators;
import org.perlonjava.operators.WarnDie;
import org.perlonjava.runtime.*;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.schema.CoreSchema;
import org.snakeyaml.engine.v2.schema.FailsafeSchema;
import org.snakeyaml.engine.v2.schema.JsonSchema;
import org.snakeyaml.engine.v2.schema.Schema;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import static org.perlonjava.runtime.RuntimeScalarType.*;

/**
 * YAML::PP module implementation for PerlonJava.
 * Provides YAML parsing and generation functionality compatible with Perl's YAML::PP module.
 */
public class YamlPP extends PerlModuleBase {

    static RuntimeScalar perlClassName = new RuntimeScalar("YAML::PP");

    /**
     * Constructor for YamlPP module.
     */
    public YamlPP() {
        super("YAML::PP", false);
    }

    /**
     * Initializes the YAML::PP module by registering methods and exports.
     */
    public static void initialize() {
        YamlPP yamlPP = new YamlPP();
        try {
            yamlPP.registerMethod("new", "new_", null);
            yamlPP.registerMethod("load_string", null);
            yamlPP.registerMethod("load_file", null);
            yamlPP.registerMethod("dump_string", null);
            yamlPP.registerMethod("dump_file", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing YAML::PP method: " + e.getMessage());
        }
    }

    /**
     * Creates a new YAML::PP instance with default settings.
     *
     * @param args Runtime arguments
     * @param ctx  Context flag
     * @return New YAML::PP instance
     */
    public static RuntimeList new_(RuntimeArray args, int ctx) {
        RuntimeHash instance = new RuntimeHash();
        RuntimeHash options = new RuntimeHash();

        args.elements.removeFirst();
        options.setFromList(args.getList());

        String schemaName = "Core";
        if (options.containsKey("schema")) {
            RuntimeScalar schemaOption = options.get("schema");
            if (schemaOption.type == RuntimeScalarType.ARRAYREFERENCE) {
                RuntimeArray schemaArray = (RuntimeArray) schemaOption.value;
                if (!schemaArray.elements.isEmpty()) {
                    schemaName = schemaArray.elements.getFirst().toString();
                }
            } else {
                schemaName = schemaOption.toString();
            }
        }

        Schema schema = switch (schemaName) {
            case "Failsafe" -> new FailsafeSchema();
            case "JSON" -> new JsonSchema();
            case "Core" -> new CoreSchema();
            default -> new CoreSchema();
        };

        CyclicRefsBehavior cyclicRefs = CyclicRefsBehavior.FATAL;
        if (options.containsKey("cyclic_refs")) {
            String cyclicRefsOption = options.get("cyclic_refs").toString().toLowerCase();
            cyclicRefs = switch (cyclicRefsOption) {
                case "warn" -> CyclicRefsBehavior.WARN;
                case "ignore" -> CyclicRefsBehavior.IGNORE;
                case "allow" -> CyclicRefsBehavior.ALLOW;
                default -> CyclicRefsBehavior.FATAL;
            };
        }

        DumpSettings dumpSettings = DumpSettings.builder()
                .setDefaultFlowStyle(FlowStyle.BLOCK)
                .setIndent(options.containsKey("indent") ? options.get("indent").getInt() : 2)
                .setExplicitStart(!options.containsKey("header") || options.get("header").getBoolean())
                .setExplicitEnd(options.containsKey("footer") && options.get("footer").getBoolean())
                .setWidth(options.containsKey("width") ? options.get("width").getInt() : 80)
                .setSchema(schema)
                .build();

        LoadSettings loadSettings = LoadSettings.builder()
                .setAllowDuplicateKeys(options.containsKey("duplicate_keys") && options.get("duplicate_keys").getBoolean())
                .setSchema(schema)
                .build();

        instance.put("_dump", new RuntimeScalar(new Dump(dumpSettings)));
        instance.put("_load", new RuntimeScalar(new Load(loadSettings)));
        instance.put("_options", new RuntimeScalar(options));
        instance.put("_cyclic_refs", new RuntimeScalar(cyclicRefs.name()));

        RuntimeScalar instanceRef = instance.createReference();
        ReferenceOperators.bless(instanceRef, new RuntimeScalar("YAML::PP"));
        return instanceRef.getList();
    }

    /**
     * Loads YAML from a string.
     *
     * @param args [instance, yaml_string]
     * @param ctx  Context flag
     * @return List of parsed YAML documents
     */
    @SuppressWarnings("unchecked")
    public static RuntimeList load_string(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        String yamlString = args.get(1).toString();

        Load load = (Load) instance.hashDeref().get("_load").value;
        Iterable<Object> documents = load.loadAllFromString(yamlString);

        RuntimeArray result = new RuntimeArray();
        for (Object doc : documents) {
            result.elements.add(convertYamlToRuntimeScalar(
                    doc,
                    new IdentityHashMap<Object, RuntimeScalar>(),
                    instance.hashDeref()));
        }
        return result.getList();
    }

    /**
     * Loads YAML from a file.
     *
     * @param args [instance, filename]
     * @param ctx  Context flag
     * @return List of parsed YAML documents
     */
    public static RuntimeList load_file(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        String filename = args.get(1).toString();

        try {
            String content = Files.readString(new File(filename).toPath());
            return load_string(new RuntimeArray(Arrays.asList(instance, new RuntimeScalar(content))), ctx);
        } catch (IOException e) {
            return WarnDie.die(new RuntimeScalar("Failed to read YAML file: " + filename), new RuntimeScalar("\n")).getList();
        }
    }

    /**
     * Dumps data structures to YAML string.
     *
     * @param args [instance, ...documents]
     * @param ctx  Context flag
     * @return YAML string representation
     */
    public static RuntimeList dump_string(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        List<Object> documents = new ArrayList<Object>();

        for (int i = 1; i < args.size(); i++) {
            documents.add(convertRuntimeScalarToYaml(
                    args.get(i),
                    new IdentityHashMap<Object, Object>()));
        }

        Dump dump = (Dump) instance.hashDeref().get("_dump").value;
        if (documents.size() == 1) {
            return new RuntimeScalar(dump.dumpToString(documents.getFirst())).getList();
        }
        return new RuntimeScalar(dump.dumpAllToString(documents.iterator())).getList();
    }

    /**
     * Dumps data structures to a YAML file.
     *
     * @param args [instance, filename, ...documents]
     * @param ctx  Context flag
     * @return Success status
     */
    public static RuntimeList dump_file(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        String filename = args.get(1).toString();
        RuntimeArray dumpArgs = new RuntimeArray();
        dumpArgs.elements.add(instance);
        for (int i = 2; i < args.size(); i++) {
            dumpArgs.elements.add(args.get(i));
        }

        String yamlContent = dump_string(dumpArgs, ctx).getFirst().toString();
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write(yamlContent);
            return RuntimeScalarCache.scalarTrue.getList();
        } catch (IOException e) {
            return WarnDie.die(new RuntimeScalar("Failed to write YAML file: " + filename), new RuntimeScalar("\n")).getList();
        }
    }

    /**
     * Converts YAML objects to RuntimeScalar representations.
     *
     * @param yaml     YAML object to convert
     * @param seen     Map of already processed objects
     * @param instance RuntimeHash instance
     * @return RuntimeScalar representation
     */
    @SuppressWarnings("unchecked")
    private static RuntimeScalar convertYamlToRuntimeScalar(Object yaml, IdentityHashMap<Object, RuntimeScalar> seen, RuntimeHash instance) {
        if (yaml == null) {
            return new RuntimeScalar();
        }

        if (seen.containsKey(yaml)) {
            String cyclicBehavior = instance.get("_cyclic_refs").toString();
            return switch (CyclicRefsBehavior.valueOf(cyclicBehavior)) {
                case FATAL ->
                        WarnDie.die(new RuntimeScalar("Cyclic reference detected in YAML structure"), new RuntimeScalar("\n")).scalar();
                case WARN -> {
                    WarnDie.warn(new RuntimeScalar("Cyclic reference detected in YAML structure"), new RuntimeScalar("\n"));
                    yield new RuntimeScalar();
                }
                case IGNORE -> new RuntimeScalar();
                case ALLOW -> seen.get(yaml);
            };
        }

        RuntimeScalar result = switch (yaml) {
            case Map<?, ?> map -> {
                RuntimeHash hash = new RuntimeHash();
                RuntimeScalar hashRef = hash.createReference();
                seen.put(yaml, hashRef);
                map.forEach((key, value) ->
                        hash.put(key.toString(), convertYamlToRuntimeScalar(value, seen, instance)));
                yield hashRef;
            }
            case List<?> list -> {
                RuntimeArray array = new RuntimeArray();
                RuntimeScalar arrayRef = array.createReference();
                seen.put(yaml, arrayRef);
                list.forEach(item ->
                        array.elements.add(convertYamlToRuntimeScalar(item, seen, instance)));
                yield arrayRef;
            }
            case String s -> new RuntimeScalar(s);
            case Integer i -> new RuntimeScalar(i);
            case Long l -> new RuntimeScalar(l);
            case Double v -> new RuntimeScalar(v);
            case Boolean b -> new RuntimeScalar(b);
            default -> new RuntimeScalar(yaml.toString());
        };

        return result;
    }

    /**
     * Converts RuntimeScalar objects to YAML-compatible representations.
     *
     * @param scalar RuntimeScalar to convert
     * @param seen   Map of already processed objects
     * @return YAML-compatible object
     */
    private static Object convertRuntimeScalarToYaml(RuntimeScalar scalar, IdentityHashMap<Object, Object> seen) {
        if (scalar == null) {
            return null;
        }

        if (seen.containsKey(scalar.value)) {
            return seen.get(scalar.value);
        }

        Object result = switch (scalar.type) {
            case HASHREFERENCE -> {
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                seen.put(scalar.value, map);
                RuntimeHash hash = (RuntimeHash) scalar.value;
                hash.elements.forEach((key, value) ->
                        map.put(key, convertRuntimeScalarToYaml(value, seen)));
                yield map;
            }
            case ARRAYREFERENCE -> {
                List<Object> list = new ArrayList<Object>();
                seen.put(scalar.value, list);
                RuntimeArray array = (RuntimeArray) scalar.value;
                array.elements.forEach(element ->
                        list.add(convertRuntimeScalarToYaml(element, seen)));
                yield list;
            }
            case STRING -> scalar.toString();
            case DOUBLE -> scalar.getDouble();
            case INTEGER -> scalar.getLong();
            case BOOLEAN -> scalar.getBoolean();
            default -> null;
        };

        return result;
    }

    private enum CyclicRefsBehavior {
        FATAL,
        WARN,
        IGNORE,
        ALLOW
    }
}
