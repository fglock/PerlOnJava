package org.perlonjava.runtime.perlmodule;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * Unicode::UCD module implementation using ICU4J.
 * Provides Unicode character database access functions.
 */
public class UnicodeUCD extends PerlModuleBase {

    public UnicodeUCD() {
        super("Unicode::UCD", true);
    }

    /**
     * Initialize the Unicode::UCD module.
     */
    public static void initialize() {
        UnicodeUCD unicodeUCD = new UnicodeUCD();
        unicodeUCD.initializeExporter();
        
        // Define exports - main function is prop_invmap
        unicodeUCD.defineExport("EXPORT_OK", 
            "prop_invmap", "prop_invlist", "prop_aliases", "prop_values",
            "charinfo", "charblock", "charscript", "charprop",
            "num", "charnames", "all_casefolds"
        );
        
        try {
            unicodeUCD.registerMethod("prop_invmap", null);
            unicodeUCD.registerMethod("prop_invlist", null);
            unicodeUCD.registerMethod("all_casefolds", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Unicode::UCD method: " + e.getMessage());
        }
    }

    /**
     * Get property inversion map for a Unicode property.
     * Returns a 4-element list: (invlist_ref, invmap_ref, format, default)
     *
     * @param args Property name
     * @param ctx  Context
     * @return RuntimeList with 4 elements
     */
    public static RuntimeList prop_invmap(RuntimeArray args, int ctx) {
        String propertyName = args.getFirst().toString();
        
        // Normalize property name (remove spaces, hyphens, underscores, case-insensitive)
        String normalizedProp = normalizePropertyName(propertyName);
        
        try {
            // Handle case mapping properties
            // Note: These use SIMPLE case mappings (single code point), not FULL mappings
            if (normalizedProp.equals("lowercasemapping") || 
                normalizedProp.equals("lc")) {
                return buildSimpleCaseMappingInvmap(UProperty.SIMPLE_LOWERCASE_MAPPING);
            }
            else if (normalizedProp.equals("uppercasemapping") || 
                     normalizedProp.equals("uc")) {
                return buildSimpleCaseMappingInvmap(UProperty.SIMPLE_UPPERCASE_MAPPING);
            }
            else if (normalizedProp.equals("titlecasemapping") || 
                     normalizedProp.equals("tc")) {
                return buildSimpleCaseMappingInvmap(UProperty.SIMPLE_TITLECASE_MAPPING);
            }
            else if (normalizedProp.equals("casefolding") || 
                     normalizedProp.equals("cf")) {
                return buildSimpleCaseMappingInvmap(UProperty.SIMPLE_CASE_FOLDING);
            }
            // Handle general category
            else if (normalizedProp.equals("generalcategory") || 
                     normalizedProp.equals("gc")) {
                return buildGeneralCategoryInvmap();
            }
            // Add more properties as needed
            else {
                // Return empty invmap for unsupported properties
                RuntimeArray invlist = new RuntimeArray();
                RuntimeArray invmap = new RuntimeArray();
                RuntimeScalar format = new RuntimeScalar("s");
                RuntimeScalar defaultVal = new RuntimeScalar(0);
                
                return new RuntimeList(
                    invlist.createReference(),
                    invmap.createReference(),
                    format,
                    defaultVal
                );
            }
        } catch (Exception e) {
            System.err.println("Error in prop_invmap: " + e.getMessage());
            e.printStackTrace();
            return new RuntimeList();
        }
    }

    /**
     * Build inversion map for case mapping properties using ICU4J.
     * Returns FULL case mappings (can be multiple code points).
     * 
     * Format "al" (adjustable list): invmap contains either:
     * - A scalar with the target code point (for simple 1-to-1 mappings)
     * - An array reference with multiple code points (for complex mappings)
     * For ranges with no change (mapped == cp), invmap is 0 (the default).
     */
    private static RuntimeList buildSimpleCaseMappingInvmap(int property) {
        RuntimeArray invlist = new RuntimeArray();
        RuntimeArray invmap = new RuntimeArray();
        
        // Track the last mapping to detect range boundaries
        String lastMappingKey = null;
        int rangeStart = -1;
        Object rangeStartMapping = null;
        
        // Determine which case mapping function to use
        FullCaseMapper mapper;
        switch (property) {
            case UProperty.SIMPLE_LOWERCASE_MAPPING:
                mapper = (cp) -> UCharacter.toLowerCase(String.valueOf(Character.toChars(cp)));
                break;
            case UProperty.SIMPLE_UPPERCASE_MAPPING:
                mapper = (cp) -> UCharacter.toUpperCase(String.valueOf(Character.toChars(cp)));
                break;
            case UProperty.SIMPLE_TITLECASE_MAPPING:
                mapper = (cp) -> {
                    int titleCp = UCharacter.toTitleCase(cp);
                    return String.valueOf(Character.toChars(titleCp));
                };
                break;
            case UProperty.SIMPLE_CASE_FOLDING:
                mapper = (cp) -> UCharacter.foldCase(String.valueOf(Character.toChars(cp)), true);
                break;
            default:
                mapper = (cp) -> String.valueOf(Character.toChars(cp));
        }
        
        // Scan all Unicode code points (BMP + supplementary)
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            // Skip surrogates
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                continue;
            }
            
            String result = mapper.map(cp);
            int[] codePoints = result.codePoints().toArray();
            
            // Determine the mapping representation
            Object mapping;
            String mappingKey;
            
            if (codePoints.length == 1 && codePoints[0] == cp) {
                // No change - use default
                mapping = 0;
                mappingKey = "default";
            } else if (codePoints.length == 1) {
                // Simple 1-to-1 mapping
                int offset = codePoints[0] - cp;
                mapping = codePoints[0];
                mappingKey = "offset:" + offset;
            } else {
                // Complex mapping (multiple code points) - store as array ref
                RuntimeArray arr = new RuntimeArray();
                for (int codePoint : codePoints) {
                    arr.push(new RuntimeScalar(codePoint));
                }
                mapping = arr.createReference();
                mappingKey = "complex:" + cp; // Each complex mapping gets its own range
            }
            
            // Start new range if mapping pattern changes
            if (!mappingKey.equals(lastMappingKey)) {
                if (rangeStart >= 0) {
                    invlist.push(new RuntimeScalar(rangeStart));
                    if (rangeStartMapping instanceof Integer) {
                        invmap.push(new RuntimeScalar((Integer) rangeStartMapping));
                    } else {
                        invmap.push((RuntimeScalar) rangeStartMapping);
                    }
                }
                rangeStart = cp;
                rangeStartMapping = mapping;
                lastMappingKey = mappingKey;
            }
        }
        
        // Add final range
        if (rangeStart >= 0) {
            invlist.push(new RuntimeScalar(rangeStart));
            if (rangeStartMapping instanceof Integer) {
                invmap.push(new RuntimeScalar((Integer) rangeStartMapping));
            } else {
                invmap.push((RuntimeScalar) rangeStartMapping);
            }
        }
        
        // Add sentinel
        invlist.push(new RuntimeScalar(0x110000));
        
        // Format "al" means adjustable list - each element in range gets +1
        RuntimeScalar format = new RuntimeScalar("al");
        RuntimeScalar defaultVal = new RuntimeScalar(0);
        
        // Return array references, not wrapped in scalars
        return new RuntimeList(
            invlist.createReference(),
            invmap.createReference(),
            format,
            defaultVal
        );
    }
    
    /**
     * Functional interface for full case mapping (can return multiple code points).
     */
    @FunctionalInterface
    private interface FullCaseMapper {
        String map(int codePoint);
    }

    /**
     * Build inversion map for General Category property.
     */
    private static RuntimeList buildGeneralCategoryInvmap() {
        RuntimeArray invlist = new RuntimeArray();
        RuntimeArray invmap = new RuntimeArray();
        
        int lastCategory = -1;
        int rangeStart = 0;
        
        // Scan all Unicode code points
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            // Skip surrogates
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                continue;
            }
            
            int category = UCharacter.getType(cp);
            
            // Start new range if category changes
            if (cp == 0 || category != lastCategory) {
                if (cp > 0) {
                    invlist.push(new RuntimeScalar(rangeStart));
                    invmap.push(new RuntimeScalar(getCategoryName(lastCategory)));
                }
                rangeStart = cp;
                lastCategory = category;
            }
        }
        
        // Add final range
        invlist.push(new RuntimeScalar(rangeStart));
        invmap.push(new RuntimeScalar(getCategoryName(lastCategory)));
        
        // Add sentinel
        invlist.push(new RuntimeScalar(0x110000));
        
        // Format "s" means string values
        RuntimeScalar format = new RuntimeScalar("s");
        RuntimeScalar defaultVal = new RuntimeScalar("Cn"); // Unassigned
        
        // Return array references, not wrapped in scalars
        return new RuntimeList(
            invlist.createReference(),
            invmap.createReference(),
            format,
            defaultVal
        );
    }

    /**
     * Get General Category name from ICU category constant.
     */
    private static String getCategoryName(int category) {
        return switch (category) {
            case UCharacter.UPPERCASE_LETTER -> "Lu";
            case UCharacter.LOWERCASE_LETTER -> "Ll";
            case UCharacter.TITLECASE_LETTER -> "Lt";
            case UCharacter.MODIFIER_LETTER -> "Lm";
            case UCharacter.OTHER_LETTER -> "Lo";
            case UCharacter.NON_SPACING_MARK -> "Mn";
            case UCharacter.ENCLOSING_MARK -> "Me";
            case UCharacter.COMBINING_SPACING_MARK -> "Mc";
            case UCharacter.DECIMAL_DIGIT_NUMBER -> "Nd";
            case UCharacter.LETTER_NUMBER -> "Nl";
            case UCharacter.OTHER_NUMBER -> "No";
            case UCharacter.SPACE_SEPARATOR -> "Zs";
            case UCharacter.LINE_SEPARATOR -> "Zl";
            case UCharacter.PARAGRAPH_SEPARATOR -> "Zp";
            case UCharacter.CONTROL -> "Cc";
            case UCharacter.FORMAT -> "Cf";
            case UCharacter.PRIVATE_USE -> "Co";
            case UCharacter.SURROGATE -> "Cs";
            case UCharacter.DASH_PUNCTUATION -> "Pd";
            case UCharacter.START_PUNCTUATION -> "Ps";
            case UCharacter.END_PUNCTUATION -> "Pe";
            case UCharacter.CONNECTOR_PUNCTUATION -> "Pc";
            case UCharacter.OTHER_PUNCTUATION -> "Po";
            case UCharacter.MATH_SYMBOL -> "Sm";
            case UCharacter.CURRENCY_SYMBOL -> "Sc";
            case UCharacter.MODIFIER_SYMBOL -> "Sk";
            case UCharacter.OTHER_SYMBOL -> "So";
            case UCharacter.INITIAL_PUNCTUATION -> "Pi";
            case UCharacter.FINAL_PUNCTUATION -> "Pf";
            default -> "Cn"; // Unassigned
        };
    }

    /**
     * Normalize property name for comparison.
     */
    private static String normalizePropertyName(String name) {
        return name.toLowerCase()
                   .replaceAll("[\\s_-]", "")
                   .replace(":", "");
    }

    /**
     * Get property inversion list (just the code point ranges).
     */
    public static RuntimeList prop_invlist(RuntimeArray args, int ctx) {
        RuntimeList fullResult = prop_invmap(args, ctx);
        // Return just the first element (invlist)
        return new RuntimeList(fullResult.elements.get(0));
    }

    /**
     * Returns all case folding mappings as a hash reference.
     * 
     * Returns a hash where keys are decimal code points and values are hash refs with:
     * - code: hex string of the code point (e.g., "0041")
     * - status: "C" (common), "F" (full), "S" (simple), "T" (turkic)
     * - simple: hex string of simple case fold (empty if none)
     * - full: hex string(s) of full case fold (space-separated if multiple)
     * - mapping: same as full
     * - turkic: hex string of turkic-specific fold (empty if none)
     *
     * @param args Unused
     * @param ctx Context
     * @return RuntimeList containing hash reference
     */
    public static RuntimeList all_casefolds(RuntimeArray args, int ctx) {
        RuntimeHash result = new RuntimeHash();
        
        // Scan all Unicode code points
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            // Skip surrogates
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                continue;
            }
            
            // Get case folding for this code point
            String folded = UCharacter.foldCase(String.valueOf(Character.toChars(cp)), true);
            int[] foldedCodePoints = folded.codePoints().toArray();
            
            // Skip if it folds to itself (no case folding)
            if (foldedCodePoints.length == 1 && foldedCodePoints[0] == cp) {
                continue;
            }
            
            // Create entry for this code point
            RuntimeHash entry = new RuntimeHash();
            
            // code: hex string of original code point
            entry.put("code", new RuntimeScalar(String.format("%04X", cp)));
            
            // Determine status and create fold strings
            String fullFold = formatCodePoints(foldedCodePoints);
            String simpleFold = "";
            String status;
            
            if (foldedCodePoints.length == 1) {
                // Simple case folding (1-to-1 mapping)
                simpleFold = fullFold;
                status = "C"; // Common
            } else {
                // Full case folding (1-to-many mapping)
                simpleFold = ""; // No simple fold for multi-char results
                status = "F"; // Full
            }
            
            entry.put("status", new RuntimeScalar(status));
            entry.put("simple", new RuntimeScalar(simpleFold));
            entry.put("full", new RuntimeScalar(fullFold));
            entry.put("mapping", new RuntimeScalar(fullFold));
            
            // Turkic-specific folding (for Turkish/Azeri)
            // ICU4J doesn't expose turkic-specific folding directly, so we leave it empty
            entry.put("turkic", new RuntimeScalar(""));
            
            // Add to result hash with decimal code point as key
            result.put(String.valueOf(cp), entry.createReference());
        }
        
        return new RuntimeList(result.createReference());
    }
    
    /**
     * Format an array of code points as space-separated hex strings.
     */
    private static String formatCodePoints(int[] codePoints) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codePoints.length; i++) {
            if (i > 0) sb.append(" ");
            sb.append(String.format("%04X", codePoints[i]));
        }
        return sb.toString();
    }
}
