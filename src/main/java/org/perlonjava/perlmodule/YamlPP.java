package org.perlonjava.perlmodule;

import org.perlonjava.operators.ReferenceOperators;
import org.perlonjava.runtime.*;
import org.snakeyaml.engine.v2.api.*;
import org.snakeyaml.engine.v2.common.FlowStyle;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * YAML::PP module implementation for PerlonJava.
 * Provides YAML parsing and generation functionality compatible with Perl's YAML::PP module.
 */
public class YamlPP extends PerlModuleBase {

    /**
     * Constructor for YamlPP module.
     */
    public YamlPP() {
        super("YAML::PP", true);
    }

    /**
     * Initializes the YAML::PP module by registering methods and exports.
     */
    public static void initialize() {
        YamlPP yamlPP = new YamlPP();
        try {
            // Register instance methods
            yamlPP.registerMethod("new", "new_", null);
            yamlPP.registerMethod("load_string", null);
            yamlPP.registerMethod("load_file", null);
            yamlPP.registerMethod("dump_string", null);
            yamlPP.registerMethod("dump_file", null);

            // Register static methods with their prototypes
            yamlPP.registerMethod("Load", "staticLoad", "$");
            yamlPP.registerMethod("Dump", "staticDump", "@");
            yamlPP.registerMethod("LoadFile", "staticLoadFile", "$");
            yamlPP.registerMethod("DumpFile", "staticDumpFile", "$@");

            // Set up exports
            yamlPP.initializeExporter();
            yamlPP.defineExport("EXPORT_OK", "Load", "Dump", "LoadFile", "DumpFile");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing YAML::PP method: " + e.getMessage());
        }
    }

    static RuntimeScalar perlClassName = new RuntimeScalar("YAML::PP");

    /**
     * Creates a new YAML::PP instance with default settings.
     * @param args Runtime arguments (unused)
     * @param ctx Context flag
     * @return New YAML::PP instance
     */
    public static RuntimeList new_(RuntimeArray args, int ctx) {
        RuntimeHash instance = new RuntimeHash();
        RuntimeHash options = new RuntimeHash();

        // Skip first argument (class name)
        args.elements.removeFirst();
        options.setFromList(args.getList());

        // Configure dump settings
        DumpSettings dumpSettings = DumpSettings.builder()
                .setDefaultFlowStyle(FlowStyle.BLOCK)
                .setIndent(options.containsKey("indent") ? options.get("indent").getInt() : 2)
                .setExplicitStart(options.containsKey("header") ? options.get("header").getBoolean() : true)
                .setExplicitEnd(options.containsKey("footer") ? options.get("footer").getBoolean() : false)
                .setWidth(options.containsKey("width") ? options.get("width").getInt() : 80)
                .build();

        // Configure load settings
        LoadSettings loadSettings = LoadSettings.builder()
                .setAllowDuplicateKeys(options.containsKey("duplicate_keys") ?
                        options.get("duplicate_keys").getBoolean() : false)
                .build();

        // Store dump and load instances
        instance.put("_dump", new RuntimeScalar(new Dump(dumpSettings)));
        instance.put("_load", new RuntimeScalar(new Load(loadSettings)));
        instance.put("_options", new RuntimeScalar(options));

        // Bless the instance into YAML::PP package
        RuntimeScalar instanceRef = instance.createReference();
        ReferenceOperators.bless(instanceRef, new RuntimeScalar("YAML::PP"));
        return instanceRef.getList();
    }

    /**
     * Loads YAML from a string.
     * @param args [instance, yaml_string]
     * @param ctx Context flag
     * @return List of parsed YAML documents
     */
    public static RuntimeList load_string(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        String yamlString = args.get(1).toString();

        Load load = (Load) instance.hashDeref().get("_load").value;
        Iterable<Object> documents = load.loadAllFromString(yamlString);

        RuntimeArray result = new RuntimeArray();
        for (Object doc : documents) {
            result.elements.add(convertYamlToRuntimeScalar(doc));
        }
        return result.getList();
    }

    /**
     * Loads YAML from a file.
     * @param args [instance, filename]
     * @param ctx Context flag
     * @return List of parsed YAML documents
     */
    public static RuntimeList load_file(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        String filename = args.get(1).toString();

        try {
            String content = Files.readString(new File(filename).toPath());
            return load_string(new RuntimeArray(Arrays.asList(instance, new RuntimeScalar(content))), ctx);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read YAML file: " + filename, e);
        }
    }

    /**
     * Dumps data structures to YAML string.
     * @param args [instance, ...documents]
     * @param ctx Context flag
     * @return YAML string representation
     */
    public static RuntimeList dump_string(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        List<Object> documents = new ArrayList<>();

        // Convert all documents to YAML
        for (int i = 1; i < args.size(); i++) {
            documents.add(convertRuntimeScalarToYaml(args.get(i)));
        }

        Dump dump = (Dump) instance.hashDeref().get("_dump").value;
        if (documents.size() == 1) {
            return new RuntimeScalar(dump.dumpToString(documents.getFirst())).getList();
        }
        return new RuntimeScalar(dump.dumpAllToString(documents.iterator())).getList();
    }

    /**
     * Dumps data structures to a YAML file.
     * @param args [instance, filename, ...documents]
     * @param ctx Context flag
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
            throw new RuntimeException("Failed to write YAML file: " + filename, e);
        }
    }

    // Static convenience methods

    /**
     * Static method to load YAML from a string.
     */
    public static RuntimeList staticLoad(RuntimeArray args, int ctx) {
        RuntimeArray newArgs = new RuntimeArray();
        newArgs.elements.add(new_(new RuntimeArray(perlClassName), ctx).getFirst());
        newArgs.elements.add(args.get(0));
        return load_string(newArgs, ctx);
    }

    /**
     * Static method to dump data to YAML string.
     */
    public static RuntimeList staticDump(RuntimeArray args, int ctx) {
        RuntimeArray newArgs = new RuntimeArray();
        newArgs.elements.add(new_(new RuntimeArray(perlClassName), ctx).getFirst());
        newArgs.elements.addAll(args.elements);
        return dump_string(newArgs, ctx);
    }

    /**
     * Static method to load YAML from a file.
     */
    public static RuntimeList staticLoadFile(RuntimeArray args, int ctx) {
        RuntimeArray newArgs = new RuntimeArray();
        newArgs.elements.add(new_(new RuntimeArray(perlClassName), ctx).getFirst());
        newArgs.elements.add(args.get(0));
        return load_file(newArgs, ctx);
    }

    /**
     * Static method to dump data to a YAML file.
     */
    public static RuntimeList staticDumpFile(RuntimeArray args, int ctx) {
        RuntimeArray newArgs = new RuntimeArray();
        newArgs.elements.add(new_(new RuntimeArray(perlClassName), ctx).getFirst());
        newArgs.elements.addAll(args.elements);
        return dump_file(newArgs, ctx);
    }

    /**
     * Converts YAML objects to RuntimeScalar representations.
     * @param yaml YAML object to convert
     * @return RuntimeScalar representation
     */
    private static RuntimeScalar convertYamlToRuntimeScalar(Object yaml) {
        if (yaml == null) {
            return new RuntimeScalar();
        }

        return switch (yaml) {
            case Map map -> {
                RuntimeHash hash = new RuntimeHash();
                map.forEach((key, value) ->
                        hash.put(key.toString(), convertYamlToRuntimeScalar(value)));
                yield hash.createReference();
            }
            case List list -> {
                RuntimeArray array = new RuntimeArray();
                list.forEach(item ->
                        array.elements.add(convertYamlToRuntimeScalar(item)));
                yield array.createReference();
            }
            case String s -> new RuntimeScalar(s);
            case Integer i -> new RuntimeScalar(i);
            case Long l -> new RuntimeScalar(l);
            case Double v -> new RuntimeScalar(v);
            case Boolean b -> new RuntimeScalar(b);
            default -> new RuntimeScalar(yaml.toString());
        };
    }

    /**
     * Converts RuntimeScalar objects to YAML-compatible representations.
     * @param scalar RuntimeScalar to convert
     * @return YAML-compatible object
     */
    private static Object convertRuntimeScalarToYaml(RuntimeScalar scalar) {
        return switch (scalar.type) {
            case HASHREFERENCE -> {
                Map<String, Object> map = new LinkedHashMap<>();
                RuntimeHash hash = (RuntimeHash) scalar.value;
                hash.elements.forEach((key, value) ->
                        map.put(key, convertRuntimeScalarToYaml(value)));
                yield map;
            }
            case ARRAYREFERENCE -> {
                List<Object> list = new ArrayList<>();
                RuntimeArray array = (RuntimeArray) scalar.value;
                array.elements.forEach(element ->
                        list.add(convertRuntimeScalarToYaml(element)));
                yield list;
            }
            case STRING -> scalar.toString();
            case DOUBLE -> scalar.getDouble();
            case INTEGER -> scalar.getLong();
            case BOOLEAN -> scalar.getBoolean();
            default -> null;
        };
    }
}
