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
            ((RuntimeBase) runtimeScalar.value).setBlessId(NameNormalizer.getBlessId(str));
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
                // For globs, check what slots are filled
                // If only one slot is filled, return the type of that slot
                RuntimeGlob glob = (RuntimeGlob) runtimeScalar.value;
                String globName = glob.globName;
                
                // Special case: stash globs (ending with ::) should always return empty string
                // because they represent the entire package stash, not a single slot
                if (globName.endsWith("::")) {
                    str = "";
                    break;
                }
                
                // Check various slots
                boolean hasScalar = GlobalVariable.getGlobalVariable(globName).getDefinedBoolean();
                boolean hasArray = GlobalVariable.getGlobalArray(globName).size() > 0;
                boolean hasHash = GlobalVariable.getGlobalHash(globName).size() > 0;
                boolean hasCode = GlobalVariable.getGlobalCodeRef(globName).getDefinedBoolean();
                boolean hasFormat = GlobalVariable.getGlobalFormatRef(globName).getDefinedBoolean();
                boolean hasIO = GlobalVariable.getGlobalIO(globName).getRuntimeIO() != null;
                
                // Special case: constant subroutine created from scalar should return SCALAR
                if (hasScalar && hasCode) {
                    RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(globName);
                    if (codeRef.value instanceof RuntimeCode code && code.constantValue != null) {
                        // This is a constant subroutine created from a scalar reference
                        // Perl returns SCALAR in this case
                        str = "SCALAR";
                        break;
                    }
                }
                
                // Count filled slots
                int filledSlots = 0;
                String slotType = "";
                if (hasScalar) { filledSlots++; slotType = "SCALAR"; }
                if (hasArray) { filledSlots++; if (slotType.isEmpty()) slotType = "ARRAY"; }
                if (hasHash) { filledSlots++; if (slotType.isEmpty()) slotType = "HASH"; }
                if (hasCode) { filledSlots++; if (slotType.isEmpty()) slotType = "CODE"; }
                if (hasFormat) { filledSlots++; if (slotType.isEmpty()) slotType = "FORMAT"; }
                if (hasIO) { filledSlots++; if (slotType.isEmpty()) slotType = "IO"; }
                
                // If exactly one slot is filled, return its type
                // Otherwise return empty string (standard Perl behavior for multi-slot globs)
                str = (filledSlots == 1) ? slotType : "";
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
                blessId = ((RuntimeBase) runtimeScalar.value).blessId;
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
