package org.perlonjava.perlmodule;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.*;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.*;

import static org.perlonjava.runtime.RuntimeScalarCache.*;
import static org.perlonjava.runtime.RuntimeScalarType.*;

/**
 * The {@code Toml} class provides methods for parsing and generating TOML data
 * within a Perl-like runtime environment. It extends {@link PerlModuleBase} and
 * offers functionality to convert between TOML strings and Perl data structures.
 * <p>
 * Note: Some methods are defined in src/main/perl/lib/TOML.pm
 */
public class Toml extends PerlModuleBase {

    /**
     * Constructs a new {@code Toml} instance and initializes the module with the name "TOML".
     */
    public Toml() {
        super("TOML", false);
    }

    /**
     * Initializes the TOML module by registering methods and defining exports.
     */
    public static void initialize() {
        Toml toml = new Toml();
        try {
            toml.registerMethod("from_toml", null);
            toml.registerMethod("to_toml", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Toml method: " + e.getMessage());
        }
    }

    /**
     * Parses a TOML string into a Perl data structure.
     * In list context, returns (hashref, error_string) where error_string is undef on success.
     * In scalar context, returns just the hashref (or undef on error).
     *
     * @param args the runtime array containing the TOML string
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the parsed data and optional error
     */
    public static RuntimeList from_toml(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new PerlCompilerException("from_toml requires a TOML string argument");
        }
        
        String tomlString = args.get(0).toString();
        
        try {
            TomlParseResult result = org.tomlj.Toml.parse(tomlString);
            
            // Check for parse errors
            if (result.hasErrors()) {
                StringBuilder errorMsg = new StringBuilder();
                result.errors().forEach(error -> {
                    if (errorMsg.length() > 0) errorMsg.append("; ");
                    errorMsg.append(error.toString());
                });
                
                // In list context, return (undef, error_string)
                if (ctx == RuntimeContextType.LIST) {
                    RuntimeList list = new RuntimeList();
                    list.add(scalarUndef);
                    list.add(new RuntimeScalar(errorMsg.toString()));
                    return list;
                }
                // In scalar context, return undef
                return scalarUndef.getList();
            }
            
            RuntimeScalar data = convertTomlToRuntimeScalar(result);
            
            // In list context, return (hashref, undef)
            if (ctx == RuntimeContextType.LIST) {
                RuntimeList list = new RuntimeList();
                list.add(data);
                list.add(scalarUndef);
                return list;
            }
            // In scalar context, return just the hashref
            return data.getList();
            
        } catch (Exception e) {
            String errorMsg = "Error parsing TOML: " + e.getMessage();
            
            if (ctx == RuntimeContextType.LIST) {
                RuntimeList list = new RuntimeList();
                list.add(scalarUndef);
                list.add(new RuntimeScalar(errorMsg));
                return list;
            }
            return scalarUndef.getList();
        }
    }

    /**
     * Converts a Perl data structure to a TOML string.
     *
     * @param args the runtime array containing the Perl data structure
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the TOML string
     */
    public static RuntimeList to_toml(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new PerlCompilerException("to_toml requires a data structure argument");
        }
        
        RuntimeScalar data = args.get(0);
        
        try {
            StringBuilder sb = new StringBuilder();
            convertRuntimeScalarToToml(data, sb, "", false);
            return new RuntimeScalar(sb.toString()).getList();
        } catch (Exception e) {
            throw new PerlCompilerException("Error generating TOML: " + e.getMessage());
        }
    }

    /**
     * Converts a TOML table/array to a {@link RuntimeScalar}.
     */
    private static RuntimeScalar convertTomlToRuntimeScalar(Object toml) {
        if (toml == null) {
            return scalarUndef;
        }
        
        if (toml instanceof TomlTable table) {
            RuntimeHash hash = new RuntimeHash();
            for (String key : table.keySet()) {
                Object value = table.get(key);
                hash.put(key, convertTomlToRuntimeScalar(value));
            }
            return hash.createReference();
        } else if (toml instanceof TomlArray array) {
            RuntimeArray runtimeArray = new RuntimeArray();
            for (int i = 0; i < array.size(); i++) {
                runtimeArray.elements.add(convertTomlToRuntimeScalar(array.get(i)));
            }
            return runtimeArray.createReference();
        } else if (toml instanceof String) {
            return new RuntimeScalar((String) toml);
        } else if (toml instanceof Long) {
            return new RuntimeScalar((Long) toml);
        } else if (toml instanceof Double) {
            return new RuntimeScalar((Double) toml);
        } else if (toml instanceof Boolean) {
            return new RuntimeScalar((Boolean) toml);
        } else if (toml instanceof OffsetDateTime) {
            return new RuntimeScalar(toml.toString());
        } else if (toml instanceof LocalDateTime) {
            return new RuntimeScalar(toml.toString());
        } else if (toml instanceof LocalDate) {
            return new RuntimeScalar(toml.toString());
        } else if (toml instanceof LocalTime) {
            return new RuntimeScalar(toml.toString());
        } else {
            return new RuntimeScalar(toml.toString());
        }
    }

    /**
     * Converts a {@link RuntimeScalar} to TOML format.
     */
    private static void convertRuntimeScalarToToml(RuntimeScalar scalar, StringBuilder sb, String prefix, boolean isArrayElement) {
        if (scalar == null || scalar.type == RuntimeScalarType.UNDEF) {
            // TOML doesn't have null, skip or use empty string
            return;
        }
        
        switch (scalar.type) {
            case HASHREFERENCE -> {
                RuntimeHash hash = (RuntimeHash) scalar.value;
                
                // Separate simple values from nested tables/arrays
                List<String> simpleKeys = new ArrayList<>();
                List<String> tableKeys = new ArrayList<>();
                
                for (String key : hash.elements.keySet()) {
                    RuntimeScalar value = hash.get(key);
                    if (value.type == RuntimeScalarType.HASHREFERENCE) {
                        tableKeys.add(key);
                    } else if (value.type == RuntimeScalarType.ARRAYREFERENCE) {
                        RuntimeArray arr = (RuntimeArray) value.value;
                        if (!arr.elements.isEmpty() && arr.elements.get(0).type == RuntimeScalarType.HASHREFERENCE) {
                            tableKeys.add(key);
                        } else {
                            simpleKeys.add(key);
                        }
                    } else {
                        simpleKeys.add(key);
                    }
                }
                
                // Output simple key-value pairs first
                for (String key : simpleKeys) {
                    RuntimeScalar value = hash.get(key);
                    sb.append(escapeKey(key)).append(" = ");
                    appendValue(value, sb);
                    sb.append("\n");
                }
                
                // Output nested tables
                for (String key : tableKeys) {
                    RuntimeScalar value = hash.get(key);
                    String newPrefix = prefix.isEmpty() ? key : prefix + "." + key;
                    
                    if (value.type == RuntimeScalarType.ARRAYREFERENCE) {
                        RuntimeArray arr = (RuntimeArray) value.value;
                        if (!arr.elements.isEmpty() && arr.elements.get(0).type == RuntimeScalarType.HASHREFERENCE) {
                            // Array of tables
                            for (RuntimeScalar element : arr.elements) {
                                sb.append("\n[[").append(newPrefix).append("]]\n");
                                convertRuntimeScalarToToml(element, sb, newPrefix, true);
                            }
                        }
                    } else {
                        // Nested table
                        sb.append("\n[").append(newPrefix).append("]\n");
                        convertRuntimeScalarToToml(value, sb, newPrefix, false);
                    }
                }
            }
            case ARRAYREFERENCE -> {
                RuntimeArray array = (RuntimeArray) scalar.value;
                sb.append("[");
                boolean first = true;
                for (RuntimeScalar element : array.elements) {
                    if (!first) sb.append(", ");
                    first = false;
                    appendValue(element, sb);
                }
                sb.append("]");
            }
            default -> appendValue(scalar, sb);
        }
    }

    /**
     * Appends a scalar value in TOML format.
     */
    private static void appendValue(RuntimeScalar scalar, StringBuilder sb) {
        switch (scalar.type) {
            case STRING, BYTE_STRING, VSTRING -> {
                sb.append("\"").append(escapeString(scalar.toString())).append("\"");
            }
            case INTEGER -> sb.append(scalar.getLong());
            case DOUBLE -> sb.append(scalar.getDouble());
            case BOOLEAN -> sb.append(scalar.getBoolean() ? "true" : "false");
            case ARRAYREFERENCE -> {
                RuntimeArray array = (RuntimeArray) scalar.value;
                sb.append("[");
                boolean first = true;
                for (RuntimeScalar element : array.elements) {
                    if (!first) sb.append(", ");
                    first = false;
                    appendValue(element, sb);
                }
                sb.append("]");
            }
            case HASHREFERENCE -> {
                RuntimeHash hash = (RuntimeHash) scalar.value;
                sb.append("{");
                boolean first = true;
                for (String key : hash.elements.keySet()) {
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append(escapeKey(key)).append(" = ");
                    appendValue(hash.get(key), sb);
                }
                sb.append("}");
            }
            default -> sb.append("\"\"");
        }
    }

    /**
     * Escapes a string for TOML output.
     */
    private static String escapeString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Escapes a key for TOML output if necessary.
     */
    private static String escapeKey(String key) {
        // If key contains special characters, quote it
        if (key.matches("[A-Za-z0-9_-]+")) {
            return key;
        }
        return "\"" + escapeString(key) + "\"";
    }
}
