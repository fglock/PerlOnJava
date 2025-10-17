package org.perlonjava.perlmodule;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.UnicodeSet;
import org.perlonjava.runtime.*;

import java.util.ArrayList;
import java.util.List;

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
            "num", "charnames"
        );
        
        try {
            unicodeUCD.registerMethod("prop_invmap", null);
            unicodeUCD.registerMethod("prop_invlist", null);
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
            if (normalizedProp.equals("lowercasemapping") || 
                normalizedProp.equals("lc")) {
                return buildCaseMappingInvmap((cp, opt) -> UCharacter.toLowerCase(cp), 0);
            }
            else if (normalizedProp.equals("uppercasemapping") || 
                     normalizedProp.equals("uc")) {
                return buildCaseMappingInvmap((cp, opt) -> UCharacter.toUpperCase(cp), 0);
            }
            else if (normalizedProp.equals("titlecasemapping") || 
                     normalizedProp.equals("tc")) {
                return buildCaseMappingInvmap((cp, opt) -> UCharacter.toTitleCase(cp), 0);
            }
            else if (normalizedProp.equals("casefolding") || 
                     normalizedProp.equals("cf")) {
                return buildCaseMappingInvmap((cp, opt) -> UCharacter.foldCase(cp, opt), UCharacter.FOLD_CASE_DEFAULT);
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
     * Build inversion map for case mapping properties.
     * 
     * Format "al" (adjustable list): invmap contains the target code point for the first
     * element of each range. Subsequent elements get +1 for each position.
     * For ranges with no change (offset=0), invmap is 0 (the default).
     * For example, if range [0x41..0x5A] has invmap value 0x61, then:
     *   0x41 -> 0x61 + 0 = 0x61
     *   0x42 -> 0x61 + 1 = 0x62
     *   etc.
     */
    private static RuntimeList buildCaseMappingInvmap(CaseMapper mapper, int options) {
        RuntimeArray invlist = new RuntimeArray();
        RuntimeArray invmap = new RuntimeArray();
        
        int lastOffset = Integer.MAX_VALUE; // Initialize to impossible value
        int rangeStart = -1;
        int rangeStartMapped = 0;
        
        // Scan all Unicode code points (BMP + supplementary)
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            // Skip surrogates
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                continue;
            }
            
            int mapped = mapper.map(cp, options);
            int offset = mapped - cp;
            
            // Start new range if offset changes
            if (offset != lastOffset) {
                if (rangeStart >= 0) {
                    invlist.push(new RuntimeScalar(rangeStart));
                    // For no-change ranges (offset=0), store 0 as the default
                    // Otherwise store the mapped value for the START of the range
                    if (lastOffset == 0) {
                        invmap.push(new RuntimeScalar(0));
                    } else {
                        invmap.push(new RuntimeScalar(rangeStartMapped));
                    }
                }
                rangeStart = cp;
                rangeStartMapped = mapped; // Target for first element
                lastOffset = offset;
            }
        }
        
        // Add final range
        if (rangeStart >= 0) {
            invlist.push(new RuntimeScalar(rangeStart));
            if (lastOffset == 0) {
                invmap.push(new RuntimeScalar(0));
            } else {
                invmap.push(new RuntimeScalar(rangeStartMapped));
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
     * Functional interface for case mapping.
     */
    @FunctionalInterface
    private interface CaseMapper {
        int map(int codePoint, int options);
    }

    /**
     * Get property inversion list (just the code point ranges).
     */
    public static RuntimeList prop_invlist(RuntimeArray args, int ctx) {
        RuntimeList fullResult = prop_invmap(args, ctx);
        // Return just the first element (invlist)
        return new RuntimeList(fullResult.elements.get(0));
    }
}
