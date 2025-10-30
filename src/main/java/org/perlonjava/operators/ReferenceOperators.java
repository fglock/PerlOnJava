package org.perlonjava.operators;

import org.perlonjava.perlmodule.Universal;
import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarEmptyString;
import static org.perlonjava.runtime.RuntimeScalarType.*;

/**
 * Provides implementations of Perl's reference-related operators.
 * This class handles operations like blessing references, checking reference types,
 * and performing inheritance checks.
 */
public class ReferenceOperators {
    /**
     * "Blesses" a Perl reference into an object by associating it with a class name.
     * This method is used to convert a Perl reference into an object of a specified class.
     *
     * @param runtimeScalar The reference to bless
     * @param className     A RuntimeScalar representing the name of the class to bless the reference into
     * @return A RuntimeScalar representing the blessed object
     * @throws PerlCompilerException if attempting to bless a non-reference value
     */
    public static RuntimeScalar bless(RuntimeScalar runtimeScalar, RuntimeScalar className) {
        if (RuntimeScalarType.isReference(runtimeScalar)) {
            // Default to "main" if className is empty
            String str = className.toString();
            if (str.isEmpty()) {
                str = "main";
            }
            int blessId = NameNormalizer.getBlessId(str);
            
            // Handle RuntimeIO (GLOBREFERENCE) specially since it doesn't extend RuntimeBase
            if (runtimeScalar.type == RuntimeScalarType.GLOBREFERENCE && runtimeScalar.value instanceof RuntimeIO) {
                // Store blessId on the RuntimeScalar itself since RuntimeIO doesn't have one
                runtimeScalar.blessId = blessId;
            } else if (runtimeScalar.value instanceof RuntimeBase rb) {
                rb.setBlessId(blessId);
            } else {
                throw new PerlCompilerException("Can't bless this type of reference");
            }
        } else {
            throw new PerlCompilerException("Can't bless non-reference value");
        }
        return runtimeScalar;
    }

    /**
     * Returns the type of a reference or its blessed package name.
     * Implements Perl's ref() built-in function.
     *
     * @param runtimeScalar The scalar to check
     * @return A RuntimeScalar containing the reference type as a string:
     * - "SCALAR", "ARRAY", "HASH", "CODE", "REF", "GLOB", "REGEXP", etc.
     * - The blessed package name if the reference is blessed
     * - Empty string if not a reference
     */
    public static RuntimeScalar ref(RuntimeScalar runtimeScalar) {
        String str;
        int blessId;
        switch (runtimeScalar.type) {
            case TIED_SCALAR:
                str = ref(runtimeScalar.tiedFetch()).toString();
                break;
            case CODE:
                blessId = ((RuntimeBase) runtimeScalar.value).blessId;
                str = blessId == 0 ? "CODE" : NameNormalizer.getBlessStr(blessId);
                break;
            case GLOB:
                str = "";
                break;
            case REGEX:
                blessId = ((RuntimeBase) runtimeScalar.value).blessId;
                str = blessId == 0 ? "Regexp" : NameNormalizer.getBlessStr(blessId);
                break;
            case REFERENCE:
                // Handle nested references
                String ref = "REF";
                if (runtimeScalar.value instanceof RuntimeScalar scalar) {
                    ref = switch (scalar.type) {
                        case VSTRING -> "VSTRING";
                        case REGEX, ARRAYREFERENCE, HASHREFERENCE, CODE, GLOBREFERENCE -> "REF";
                        case GLOB -> "GLOB";
                        default -> "SCALAR";
                    };
                }
                blessId = ((RuntimeBase) runtimeScalar.value).blessId;
                str = blessId == 0 ? ref : NameNormalizer.getBlessStr(blessId);
                break;
            case ARRAYREFERENCE:
                blessId = ((RuntimeBase) runtimeScalar.value).blessId;
                str = blessId == 0 ? "ARRAY" : NameNormalizer.getBlessStr(blessId);
                break;
            case HASHREFERENCE:
                blessId = ((RuntimeBase) runtimeScalar.value).blessId;
                str = blessId == 0 ? "HASH" : NameNormalizer.getBlessStr(blessId);
                break;
            case GLOBREFERENCE:
                // Handle RuntimeIO specially - blessId is on the wrapper RuntimeScalar
                if (runtimeScalar.value instanceof RuntimeIO) {
                    blessId = runtimeScalar.blessId;
                } else {
                    blessId = ((RuntimeBase) runtimeScalar.value).blessId;
                }
                str = blessId == 0 ? "GLOB" : NameNormalizer.getBlessStr(blessId);
                break;
            default:
                return scalarEmptyString;
        }
        return new RuntimeScalar(str);
    }

    /**
     * Implements Perl's isa operator to check if an object belongs to a class or inherits from it.
     * Delegates the actual check to Universal.isa().
     *
     * @param runtimeScalar The object to check
     * @param className     The class name to check against
     * @return A RuntimeScalar containing the boolean result of the inheritance check
     */
    public static RuntimeScalar isa(RuntimeScalar runtimeScalar, RuntimeScalar className) {
        RuntimeArray args = new RuntimeArray();

        if (blessedId(runtimeScalar) != 0) {
            RuntimeArray.push(args, className);
            return RuntimeCode.call(
                    runtimeScalar,
                    new RuntimeScalar("isa"),
                    null,
                    args,
                    RuntimeContextType.SCALAR).scalar();
        }

        RuntimeArray.push(args, runtimeScalar);
        RuntimeArray.push(args, className);
        return Universal.isa(args, RuntimeContextType.SCALAR).scalar();
    }
}
