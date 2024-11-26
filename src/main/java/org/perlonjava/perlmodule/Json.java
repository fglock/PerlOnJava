package org.perlonjava.perlmodule;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.perlonjava.runtime.*;

import java.math.BigDecimal;
import java.util.List;

public class Json extends PerlModuleBase {

    public Json() {
        super("JSON");
    }

    public static void initialize() {
        Json json = new Json();
        json.initializeExporter();
        json.defineExport("EXPORT", "encode_json", "decode_json", "to_json", "from_json");
        try {
            json.registerMethod("encode_json", "$");
            json.registerMethod("decode_json", "$");
            json.registerMethod("to_json", "$");
            json.registerMethod("from_json", "$");
            json.registerMethod("pretty", "$");
            json.registerMethod("indent", "$");
            json.registerMethod("space_before", "$");
            json.registerMethod("space_after", "$");
            json.registerMethod("new", "newInstance", "");
            json.registerMethod("encode", "$");
            json.registerMethod("decode", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Json method: " + e.getMessage());
        }
    }

    public static RuntimeList encode_json(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for encode_json");
        }
        RuntimeScalar perlData = args.get(0);
        RuntimeScalar jsonObject = (RuntimeScalar) newInstance(
                new RuntimeArray(new RuntimeScalar("JSON")),
                RuntimeContextType.SCALAR).elements.getFirst();
        return encode(
                new RuntimeArray(List.of(jsonObject, perlData)),
                RuntimeContextType.SCALAR);
    }

    public static RuntimeList encode(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for Json method");
        }
        RuntimeScalar instance = args.get(0);
        RuntimeScalar perlData = args.get(1);
        Object json = convertRuntimeScalarToJson(perlData);

        // Retrieve the instance settings
        RuntimeHash hash = instance.hashDeref();
        boolean indent = hash.get("indent").getBoolean();
        boolean spaceBefore = hash.get("space_before").getBoolean();
        boolean spaceAfter = hash.get("space_after").getBoolean();

        // Configure JSON serialization options
        JSONWriter.Feature[] features = indent ? new JSONWriter.Feature[]{JSONWriter.Feature.PrettyFormat} : new JSONWriter.Feature[0];

        // Serialize JSON with the configured options
        String jsonString = JSON.toJSONString(json, features);

        // Post-process the JSON string for custom indentation
        if (indent) {
            jsonString = jsonString.replaceAll("\t", "   "); // Replace default indentation with 3 spaces
        }

        // Post-process the JSON string for space_before and space_after
        if (spaceBefore || spaceAfter) {
            jsonString = jsonString.replaceAll(":", spaceBefore ? " : " : ":");
            jsonString = jsonString.replaceAll(",", spaceAfter ? ", " : ",");
        }

        return new RuntimeScalar(jsonString).getList();
    }

    public static RuntimeList decode_json(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for Json method");
        }
        RuntimeScalar jsonString = args.get(0);
        Object json = JSON.parse(jsonString.toString());
        return convertJsonToRuntimeScalar(json).getList();
    }

    public static RuntimeList decode(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for Json method");
        }
        RuntimeScalar instance = args.get(0);
        RuntimeScalar jsonString = args.get(1);
        Object json = JSON.parse(jsonString.toString());
        return convertJsonToRuntimeScalar(json).getList();
    }

    public static RuntimeList to_json(RuntimeArray args, int ctx) {
        return encode_json(args, ctx);
    }

    public static RuntimeList from_json(RuntimeArray args, int ctx) {
        return decode_json(args, ctx);
    }


    public static RuntimeList newInstance(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for JSON->new");
        }
        RuntimeScalar className = args.get(0);
        RuntimeScalar instance = new RuntimeHash().createReference();
        instance.bless(className);
        return instance.getList();
    }

    public static RuntimeList pretty(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        boolean enable = args.size() == 2 ? args.get(1).getBoolean() : true;
        RuntimeHash hash = instance.hashDeref();
        hash.get("indent").set(new RuntimeScalar(enable));
        hash.get("space_before").set(new RuntimeScalar(enable));
        hash.get("space_after").set(new RuntimeScalar(enable));
        return instance.getList();
    }

    public static RuntimeList indent(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        boolean enable = args.size() == 2 ? args.get(1).getBoolean() : true;
        RuntimeHash hash = instance.hashDeref();
        hash.get("indent").set(new RuntimeScalar(enable));
        return instance.getList();
    }

    public static RuntimeList get_indent(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        RuntimeHash hash = instance.hashDeref();
        return hash.get("indent").getList();
    }

    public static RuntimeList space_before(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        boolean enable = args.size() == 2 ? args.get(1).getBoolean() : true;
        RuntimeHash hash = instance.hashDeref();
        hash.get("space_before").set(new RuntimeScalar(enable));
        return instance.getList();
    }

    public static RuntimeList get_space_before(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        RuntimeHash hash = instance.hashDeref();
        return hash.get("space_before").getList();
    }

    public static RuntimeList space_after(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        boolean enable = args.size() == 2 ? args.get(1).getBoolean() : true;
        RuntimeHash hash = instance.hashDeref();
        hash.get("space_after").set(new RuntimeScalar(enable));
        return instance.getList();
    }

    public static RuntimeList get_space_after(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        RuntimeHash hash = instance.hashDeref();
        return hash.get("space_after").getList();
    }

    private static RuntimeScalar convertJsonToRuntimeScalar(Object json) {
        if (json instanceof JSONObject) {
            RuntimeHash hash = new RuntimeHash();
            JSONObject jsonObject = (JSONObject) json;
            for (String key : jsonObject.keySet()) {
                hash.put(key, convertJsonToRuntimeScalar(jsonObject.get(key)));
            }
            return hash.createReference();
        } else if (json instanceof JSONArray) {
            RuntimeArray array = new RuntimeArray();
            JSONArray jsonArray = (JSONArray) json;
            for (int i = 0; i < jsonArray.size(); i++) {
                array.elements.add(convertJsonToRuntimeScalar(jsonArray.get(i)));
            }
            return array.createReference();
        } else if (json instanceof String) {
            return new RuntimeScalar((String) json);
        } else if (json instanceof Integer) {
            return new RuntimeScalar((Integer) json);
        } else if (json instanceof Long) {
            return new RuntimeScalar((Long) json);
        } else if (json instanceof Double) {
            return new RuntimeScalar((Double) json);
        } else if (json instanceof Boolean) {
            return new RuntimeScalar((Boolean) json);
        } else if (json instanceof BigDecimal) {
            // Convert BigDecimal to double
            return new RuntimeScalar(((BigDecimal) json).doubleValue());
        } else {
            return new RuntimeScalar(); // Represents null or undefined
        }
    }

    private static Object convertRuntimeScalarToJson(RuntimeScalar scalar) {
        switch (scalar.type) {
            case HASHREFERENCE:
                JSONObject jsonObject = new JSONObject();
                RuntimeHash hash = (RuntimeHash) scalar.value;
                for (String key : hash.elements.keySet()) {
                    jsonObject.put(key, convertRuntimeScalarToJson(hash.get(key)));
                }
                return jsonObject;
            case ARRAYREFERENCE:
                JSONArray jsonArray = new JSONArray();
                RuntimeArray array = (RuntimeArray) scalar.value;
                for (RuntimeScalar element : array.elements) {
                    jsonArray.add(convertRuntimeScalarToJson(element));
                }
                return jsonArray;
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
