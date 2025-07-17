package org.perlonjava.runtime;

/**
 * Constants for Runtime type descriptors and class names used in bytecode generation.
 *
 * <p>This class provides centralized definitions for JVM type descriptors and internal
 * class names for all Runtime types in the PerlOnJava runtime system.</p>
 *
 * <p>Type descriptors (e.g., "Lorg/perlonjava/runtime/RuntimeScalar;") are used
 * when describing types in method signatures and for type checking.</p>
 *
 * <p>Internal class names (e.g., "org/perlonjava/runtime/RuntimeScalar") are used
 * when generating bytecode instructions that reference classes.</p>
 */
public final class RuntimeTypeConstants {

    // Prevent instantiation
    private RuntimeTypeConstants() {}

    // ===== JVM Type Descriptors =====
    // These are used in method signatures and type checking

    /** JVM type descriptor for RuntimeScalar */
    public static final String SCALAR_TYPE = "Lorg/perlonjava/runtime/RuntimeScalar;";

    /** JVM type descriptor for RuntimeArray */
    public static final String ARRAY_TYPE = "Lorg/perlonjava/runtime/RuntimeArray;";

    /** JVM type descriptor for RuntimeHash */
    public static final String HASH_TYPE = "Lorg/perlonjava/runtime/RuntimeHash;";

    /** JVM type descriptor for RuntimeList */
    public static final String LIST_TYPE = "Lorg/perlonjava/runtime/RuntimeList;";

    /** JVM type descriptor for RuntimeGlob */
    public static final String GLOB_TYPE = "Lorg/perlonjava/runtime/RuntimeGlob;";

    /** JVM type descriptor for RuntimeCode */
    public static final String CODE_TYPE = "Lorg/perlonjava/runtime/RuntimeCode;";

    public static final String BASE_TYPE = "Lorg/perlonjava/runtime/RuntimeBase;";

    // ===== Internal Class Names =====
    // These are used in bytecode instructions (without L prefix and ; suffix)

    /** Internal class name for RuntimeScalar */
    public static final String SCALAR_CLASS = "org/perlonjava/runtime/RuntimeScalar";

    /** Internal class name for RuntimeArray */
    public static final String ARRAY_CLASS = "org/perlonjava/runtime/RuntimeArray";

    /** Internal class name for RuntimeHash */
    public static final String HASH_CLASS = "org/perlonjava/runtime/RuntimeHash";

    /** Internal class name for RuntimeList */
    public static final String LIST_CLASS = "org/perlonjava/runtime/RuntimeList";

    /** Internal class name for RuntimeGlob */
    public static final String GLOB_CLASS = "org/perlonjava/runtime/RuntimeGlob";

    /** Internal class name for RuntimeCode */
    public static final String CODE_CLASS = "org/perlonjava/runtime/RuntimeCode";

    /** Internal class name for RuntimeBase interface */
    public static final String BASE_CLASS = "org/perlonjava/runtime/RuntimeBase";

    // ===== Utility Methods =====

    /**
     * Checks if a type descriptor represents a known Runtime type.
     *
     * @param typeDescriptor The JVM type descriptor to check
     * @return true if the descriptor represents a known Runtime type
     */
    public static boolean isKnownRuntimeType(String typeDescriptor) {
        return typeDescriptor != null && (
            typeDescriptor.equals(SCALAR_TYPE) ||
            typeDescriptor.equals(ARRAY_TYPE) ||
            typeDescriptor.equals(HASH_TYPE) ||
            typeDescriptor.equals(LIST_TYPE) ||
            typeDescriptor.equals(GLOB_TYPE) ||
            typeDescriptor.equals(CODE_TYPE)
        );
    }

    /**
     * Converts a JVM type descriptor to an internal class name.
     * For example: "Lorg/perlonjava/runtime/RuntimeScalar;" → "org/perlonjava/runtime/RuntimeScalar"
     *
     * @param typeDescriptor The JVM type descriptor
     * @return The internal class name, or null if not a valid object type descriptor
     */
    public static String descriptorToInternalName(String typeDescriptor) {
        if (typeDescriptor != null && typeDescriptor.startsWith("L") && typeDescriptor.endsWith(";")) {
            return typeDescriptor.substring(1, typeDescriptor.length() - 1);
        }
        return null;
    }
}