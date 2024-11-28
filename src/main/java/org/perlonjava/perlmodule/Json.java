package org.perlonjava.perlmodule;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.perlonjava.runtime.*;

import java.math.BigDecimal;

import static org.perlonjava.runtime.RuntimeScalarCache.*;

/**
 * The {@code Json} class provides methods for encoding and decoding JSON data
 * within a Perl-like runtime environment. It extends {@link PerlModuleBase} and
 * offers functionality to convert between JSON strings and Perl data structures.
 */
public class Json extends PerlModuleBase {

    /**
     * Constructs a new {@code Json} instance and initializes the module with the name "JSON".
     */
    public Json() {
        super("JSON", false);
    }

    /**
     * Initializes the JSON module by registering methods and defining exports.
     */
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
            json.registerMethod("encode", "$");
            json.registerMethod("decode", "$");
            json.registerMethod("true", "getTrue", "");
            json.registerMethod("false", "getFalse", "");
            json.registerMethod("null", "getNull", "");
            json.registerMethod("is_bool", "isBool", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Json method: " + e.getMessage());
        }
    }

    /**
     * Checks if the given argument is a boolean.
     *
     * @param args the runtime array containing the argument to check
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} indicating whether the argument is a boolean
     */
    public static RuntimeList isBool(RuntimeArray args, int ctx) {
        RuntimeScalar res = args.get(0);
        return getScalarBoolean(res.type == RuntimeScalarType.BOOLEAN).getList();
    }

    /**
     * Returns a {@link RuntimeList} representing the boolean value true.
     *
     * @param args the runtime array
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} representing true
     */
    public static RuntimeList getTrue(RuntimeArray args, int ctx) {
        return scalarTrue.getList();
    }

    /**
     * Returns a {@link RuntimeList} representing the boolean value false.
     *
     * @param args the runtime array
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} representing false
     */
    public static RuntimeList getFalse(RuntimeArray args, int ctx) {
        return scalarFalse.getList();
    }

    /**
     * Returns a {@link RuntimeList} representing a null value.
     *
     * @param args the runtime array
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} representing null
     */
    public static RuntimeList getNull(RuntimeArray args, int ctx) {
        return scalarUndef.getList();
    }

    /**
     * Encodes a Perl data structure into a JSON string.
     *
     * @param args the runtime array containing the Perl data structure
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the JSON string
     * @throws IllegalStateException if the number of arguments is incorrect
     */
    public static RuntimeList encode_json(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for encode_json");
        }
        RuntimeScalar perlData = args.get(0);
        RuntimeScalar jsonObject = (RuntimeScalar) GlobalVariable.getGlobalCodeRef("JSON::new").apply(
                        new RuntimeArray(new RuntimeScalar("JSON")),
                        RuntimeContextType.SCALAR)
                .elements.getFirst();
        return encode(
                new RuntimeArray(jsonObject, perlData),
                RuntimeContextType.SCALAR);
    }

    /**
     * Encodes a Perl data structure into a JSON string with specific formatting options.
     *
     * @param args the runtime array containing the instance and Perl data structure
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the JSON string
     * @throws IllegalStateException if the number of arguments is incorrect
     */
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
        if (spaceBefore) {
            jsonString = jsonString.replaceAll(":", " :");
        }
        if (spaceAfter) {
            jsonString = jsonString.replaceAll(",", ", ");
            jsonString = jsonString.replaceAll(":", ": ");
        }

        return new RuntimeScalar(jsonString).getList();
    }

    /**
     * Decodes a JSON string into a Perl data structure.
     *
     * @param args the runtime array containing the JSON string
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the Perl data structure
     * @throws IllegalStateException if the number of arguments is incorrect
     */
    public static RuntimeList decode_json(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for Json method");
        }
        RuntimeScalar jsonString = args.get(0);
        Object json = JSON.parse(jsonString.toString());
        return convertJsonToRuntimeScalar(json).getList();
    }

    /**
     * Decodes a JSON string into a Perl data structure with specific instance settings.
     *
     * @param args the runtime array containing the instance and JSON string
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the Perl data structure
     * @throws IllegalStateException if the number of arguments is incorrect
     */
    public static RuntimeList decode(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for Json method");
        }
        RuntimeScalar instance = args.get(0);
        RuntimeScalar jsonString = args.get(1);
        Object json = JSON.parse(jsonString.toString());
        return convertJsonToRuntimeScalar(json).getList();
    }

    /**
     * Converts a Perl data structure to a JSON string.
     *
     * @param args the runtime array containing the Perl data structure
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the JSON string
     */
    public static RuntimeList to_json(RuntimeArray args, int ctx) {
        return encode_json(args, ctx);
    }

    /**
     * Converts a JSON string to a Perl data structure.
     *
     * @param args the runtime array containing the JSON string
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the Perl data structure
     */
    public static RuntimeList from_json(RuntimeArray args, int ctx) {
        return decode_json(args, ctx);
    }

    /**
     * Enables or disables pretty printing for a JSON instance.
     *
     * @param args the runtime array containing the instance and optional enable flag
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the updated instance
     */
    public static RuntimeList pretty(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        boolean enable = args.size() != 2 || args.get(1).getBoolean();
        RuntimeHash hash = instance.hashDeref();
        hash.get("indent").set(new RuntimeScalar(enable));
        hash.get("space_before").set(new RuntimeScalar(enable));
        hash.get("space_after").set(new RuntimeScalar(enable));
        return instance.getList();
    }

    /**
     * Enables or disables indentation for a JSON instance.
     *
     * @param args the runtime array containing the instance and optional enable flag
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the updated instance
     */
    public static RuntimeList indent(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        boolean enable = args.size() != 2 || args.get(1).getBoolean();
        RuntimeHash hash = instance.hashDeref();
        hash.get("indent").set(new RuntimeScalar(enable));
        return instance.getList();
    }

    /**
     * Retrieves the indentation setting for a JSON instance.
     *
     * @param args the runtime array containing the instance
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the indentation setting
     */
    public static RuntimeList get_indent(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        RuntimeHash hash = instance.hashDeref();
        return hash.get("indent").getList();
    }

    /**
     * Enables or disables space before colons for a JSON instance.
     *
     * @param args the runtime array containing the instance and optional enable flag
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the updated instance
     */
    public static RuntimeList space_before(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        boolean enable = args.size() != 2 || args.get(1).getBoolean();
        RuntimeHash hash = instance.hashDeref();
        hash.get("space_before").set(new RuntimeScalar(enable));
        return instance.getList();
    }

    /**
     * Retrieves the space before setting for a JSON instance.
     *
     * @param args the runtime array containing the instance
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the space before setting
     */
    public static RuntimeList get_space_before(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        RuntimeHash hash = instance.hashDeref();
        return hash.get("space_before").getList();
    }

    /**
     * Enables or disables space after commas and colons for a JSON instance.
     *
     * @param args the runtime array containing the instance and optional enable flag
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the updated instance
     */
    public static RuntimeList space_after(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        boolean enable = args.size() != 2 || args.get(1).getBoolean();
        RuntimeHash hash = instance.hashDeref();
        hash.get("space_after").set(new RuntimeScalar(enable));
        return instance.getList();
    }

    /**
     * Retrieves the space after setting for a JSON instance.
     *
     * @param args the runtime array containing the instance
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the space after setting
     */
    public static RuntimeList get_space_after(RuntimeArray args, int ctx) {
        RuntimeScalar instance = args.get(0);
        RuntimeHash hash = instance.hashDeref();
        return hash.get("space_after").getList();
    }

    /**
     * Converts a JSON object to a {@link RuntimeScalar}.
     *
     * @param json the JSON object to convert
     * @return a {@link RuntimeScalar} representing the JSON object
     */
    private static RuntimeScalar convertJsonToRuntimeScalar(Object json) {
        if (json instanceof JSONObject jsonObject) {
            RuntimeHash hash = new RuntimeHash();
            for (String key : jsonObject.keySet()) {
                hash.put(key, convertJsonToRuntimeScalar(jsonObject.get(key)));
            }
            return hash.createReference();
        } else if (json instanceof JSONArray jsonArray) {
            RuntimeArray array = new RuntimeArray();
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

    /**
     * Converts a {@link RuntimeScalar} to a JSON object.
     *
     * @param scalar the {@link RuntimeScalar} to convert
     * @return the JSON object representation of the scalar
     */
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
