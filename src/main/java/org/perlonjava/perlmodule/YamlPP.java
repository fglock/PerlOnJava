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

public class YamlPP extends PerlModuleBase {

    public YamlPP() {
        super("YAML::PP", true);
    }

    public static void initialize() {
        YamlPP yamlPP = new YamlPP();
        try {
            yamlPP.registerMethod("new", "new_", null);
            yamlPP.registerMethod("load_string", null);
            yamlPP.registerMethod("load_file", null);
            yamlPP.registerMethod("dump_string", null);
            yamlPP.registerMethod("dump_file", null);
            yamlPP.registerMethod("Load", "staticLoad", "$");
            yamlPP.registerMethod("Dump", "staticDump", "@");
            yamlPP.registerMethod("LoadFile", "staticLoadFile", "$");
            yamlPP.registerMethod("DumpFile", "staticDumpFile", "$@");

            yamlPP.initializeExporter();
            yamlPP.defineExport("EXPORT", "Load", "Dump", "LoadFile", "DumpFile");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing YAML::PP method: " + e.getMessage());
        }
    }

    public static RuntimeList new_(RuntimeArray args, int ctx) {
        RuntimeHash instance = new RuntimeHash();
        DumpSettings settings = DumpSettings.builder()
                .setDefaultFlowStyle(FlowStyle.BLOCK)
                .build();
        LoadSettings loadSettings = LoadSettings.builder().build();
        instance.put("_dump", new RuntimeScalar(new Dump(settings)));
        instance.put("_load", new RuntimeScalar(new Load(loadSettings)));
        RuntimeScalar instanceRef = instance.createReference();
        ReferenceOperators.bless(instanceRef, new RuntimeScalar("YAML::PP"));
        return instanceRef.getList();
    }

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

    public static RuntimeList dump_string(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        List<Object> documents = new ArrayList<>();

        for (int i = 1; i < args.size(); i++) {
            documents.add(convertRuntimeScalarToYaml(args.get(i)));
        }

        Dump dump = (Dump) instance.hashDeref().get("_dump").value;
        if (documents.size() == 1) {
            return new RuntimeScalar(dump.dumpToString(documents.getFirst())).getList();
        }
        return new RuntimeScalar(dump.dumpAllToString(documents.iterator())).getList();
    }

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

    // Static methods remain the same but use the new engine internally
    public static RuntimeList staticLoad(RuntimeArray args, int ctx) {
        RuntimeArray newArgs = new RuntimeArray();
        newArgs.elements.add(new_(new RuntimeArray(), ctx).getFirst());
        newArgs.elements.add(args.get(0));
        return load_string(newArgs, ctx);
    }

    public static RuntimeList staticDump(RuntimeArray args, int ctx) {
        RuntimeArray newArgs = new RuntimeArray();
        newArgs.elements.add(new_(new RuntimeArray(), ctx).getFirst());
        newArgs.elements.addAll(args.elements);
        return dump_string(newArgs, ctx);
    }

    public static RuntimeList staticLoadFile(RuntimeArray args, int ctx) {
        RuntimeArray newArgs = new RuntimeArray();
        newArgs.elements.add(new_(new RuntimeArray(), ctx).getFirst());
        newArgs.elements.add(args.get(0));
        return load_file(newArgs, ctx);
    }

    public static RuntimeList staticDumpFile(RuntimeArray args, int ctx) {
        RuntimeArray newArgs = new RuntimeArray();
        newArgs.elements.add(new_(new RuntimeArray(), ctx).getFirst());
        newArgs.elements.addAll(args.elements);
        return dump_file(newArgs, ctx);
    }

    private static RuntimeScalar convertYamlToRuntimeScalar(Object yaml) {
        if (yaml instanceof Map) {
            RuntimeHash hash = new RuntimeHash();
            ((Map<?, ?>) yaml).forEach((key, value) ->
                    hash.put(key.toString(), convertYamlToRuntimeScalar(value)));
            return hash.createReference();
        } else if (yaml instanceof List) {
            RuntimeArray array = new RuntimeArray();
            ((List<?>) yaml).forEach(item ->
                    array.elements.add(convertYamlToRuntimeScalar(item)));
            return array.createReference();
        } else if (yaml instanceof String) {
            return new RuntimeScalar((String) yaml);
        } else if (yaml instanceof Integer) {
            return new RuntimeScalar((Integer) yaml);
        } else if (yaml instanceof Long) {
            return new RuntimeScalar((Long) yaml);
        } else if (yaml instanceof Double) {
            return new RuntimeScalar((Double) yaml);
        } else if (yaml instanceof Boolean) {
            return new RuntimeScalar((Boolean) yaml);
        } else if (yaml == null) {
            return new RuntimeScalar();
        }
        return new RuntimeScalar(yaml.toString());
    }

    private static Object convertRuntimeScalarToYaml(RuntimeScalar scalar) {
        switch (scalar.type) {
            case HASHREFERENCE:
                Map<String, Object> map = new LinkedHashMap<>();
                RuntimeHash hash = (RuntimeHash) scalar.value;
                hash.elements.forEach((key, value) ->
                        map.put(key, convertRuntimeScalarToYaml(value)));
                return map;
            case ARRAYREFERENCE:
                List<Object> list = new ArrayList<>();
                RuntimeArray array = (RuntimeArray) scalar.value;
                array.elements.forEach(element ->
                        list.add(convertRuntimeScalarToYaml(element)));
                return list;
            case STRING:
                return scalar.toString();
            case DOUBLE:
                return scalar.getDouble();
            case INTEGER:
                return scalar.getLong();
            case BOOLEAN:
                return scalar.getBoolean();
            default:
                return null;
        }
    }
}
