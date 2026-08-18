/*
 * Generated from Perl 5.44's pinned Unicode Character Database. Do not edit manually.
 *
 * Source: PropList-17.0.0.txt
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 *
 * Source: Unikemet-17.0.0.txt
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 *
 * Source: PropertyValueAliases-17.0.0.txt
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 *
 * Source: PropertyAliases-17.0.0.txt
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 *
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;

final class PerlUnicodeSpecializedBinaryData {
    static final String UNICODE_VERSION = "17.0.0";
    static final String PROP_LIST_SHA256 = "130dcddcaadaf071008bdfce1e7743e04fdfbc910886f017d9f9ac931d8c64dd";
    static final String UNIKEMET_SHA256 = "76a3081265e6eb673873f9c93d6f36062e82c7ed027c5c1a592accfbe48c20a5";
    static final String PROP_VALUE_ALIASES_SHA256 = "670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01";
    static final String PROPERTY_ALIASES_SHA256 = "4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb";

    private static final String[] SHORT_NAMES = {
        "Hyphen", "kEH_NoMirror", "kEH_NoRotate", "ID_Compat_Math_Continue", "ID_Compat_Math_Start", "IDSU", "MCM"
    };
    private static final String[] LONG_NAMES = {
        "Hyphen", "kEH_NoMirror", "kEH_NoRotate", "ID_Compat_Math_Continue", "ID_Compat_Math_Start", "IDS_Unary_Operator", "Modifier_Combining_Mark"
    };
    private static final boolean[] DEPRECATED = {
        true, false, false, false, false, false, false
    };
    private static final boolean[] CONTRIBUTORY = {
        false, true, true, true, true, false, true
    };
    private static final boolean[] NEW_IN_UNICODE_17 = {
        false, true, true, false, false, true, false
    };
    private static final String[] PROPERTY_ALIASES = {
        "hyphen", "kehnomirror", "kehnorotate", "idcompatmathcontinue", "idcompatmathstart", "idsu", "idsunaryoperator", "mcm", "modifiercombiningmark"
    };
    private static final byte[] PROPERTY_ALIAS_INDEX = {
        0, 1, 2, 3, 4, 5, 5, 6, 6
    };
    private static final int[][] YES_RANGES = {
        { 0x2D, 0x2D, 0xAD, 0xAD, 0x58A, 0x58A, 0x1806, 0x1806, 0x2010, 0x2011, 0x2E17, 0x2E17, 0x30FB, 0x30FB, 0xFE63, 0xFE63, 0xFF0D, 0xFF0D, 0xFF65, 0xFF65 },
        { 0x13081, 0x13081, 0x13084, 0x13084, 0x130BB, 0x130BB, 0x130BD, 0x130BD },
        { 0x13021, 0x13021, 0x1303F, 0x1303F, 0x130AD, 0x130AD, 0x130B7, 0x130B7, 0x13131, 0x13131, 0x131B1, 0x131B1, 0x131EF, 0x131EF, 0x1327D, 0x1327D, 0x13285, 0x13285, 0x1328A, 0x1328A, 0x1329B, 0x1329C, 0x13308, 0x13309, 0x13355, 0x13355, 0x13361, 0x13361, 0x13371, 0x13372, 0x13403, 0x13403, 0x1342A, 0x1342B, 0x13489, 0x13489, 0x134BC, 0x134BC, 0x134BF, 0x134BF, 0x13948, 0x13948, 0x1394A, 0x1394A, 0x13A12, 0x13A13, 0x13A16, 0x13A16, 0x13BD5, 0x13BD5, 0x13E0F, 0x13E0F, 0x13EC2, 0x13EC2, 0x13F72, 0x13F73, 0x1401F, 0x1401F, 0x140A4, 0x140A4, 0x14190, 0x14190, 0x1425E, 0x1425E, 0x1431C, 0x1431C, 0x14388, 0x14388, 0x14399, 0x1439B, 0x143E8, 0x143E8 },
        { 0xB2, 0xB3, 0xB9, 0xB9, 0x2070, 0x2070, 0x2074, 0x207E, 0x2080, 0x208E, 0x2202, 0x2202, 0x2207, 0x2207, 0x221E, 0x221E, 0x1D6C1, 0x1D6C1, 0x1D6DB, 0x1D6DB, 0x1D6FB, 0x1D6FB, 0x1D715, 0x1D715, 0x1D735, 0x1D735, 0x1D74F, 0x1D74F, 0x1D76F, 0x1D76F, 0x1D789, 0x1D789, 0x1D7A9, 0x1D7A9, 0x1D7C3, 0x1D7C3 },
        { 0x2202, 0x2202, 0x2207, 0x2207, 0x221E, 0x221E, 0x1D6C1, 0x1D6C1, 0x1D6DB, 0x1D6DB, 0x1D6FB, 0x1D6FB, 0x1D715, 0x1D715, 0x1D735, 0x1D735, 0x1D74F, 0x1D74F, 0x1D76F, 0x1D76F, 0x1D789, 0x1D789, 0x1D7A9, 0x1D7A9, 0x1D7C3, 0x1D7C3 },
        { 0x2FFE, 0x2FFF },
        { 0x654, 0x655, 0x658, 0x658, 0x6DC, 0x6DC, 0x6E3, 0x6E3, 0x6E7, 0x6E8, 0x8CA, 0x8CB, 0x8CD, 0x8CF, 0x8D3, 0x8D3, 0x8F3, 0x8F3 },
    };
    static final int[] POSITIVE_CODE_POINT_COUNTS = {
        11, 4, 44, 43, 13, 2, 14
    };
    private static final UnicodeSet[] YES_SETS = buildYesSets();

    static boolean isPropertyAlias(String alias) { return propertyIndex(alias) >= 0; }
    static UnicodeSet yesSet(String alias) {
        int property = propertyIndex(alias);
        return property < 0 ? null : YES_SETS[property];
    }
    static UnicodeSet valueSet(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        Boolean yes = binaryValue(valueAlias);
        if (property < 0 || yes == null) return null;
        return yes ? YES_SETS[property]
                : new UnicodeSet(0, 0x10FFFF).removeAll(YES_SETS[property]).freeze();
    }
    static String shortName(String alias) {
        int property = propertyIndex(alias); return property < 0 ? null : SHORT_NAMES[property];
    }
    static String canonicalName(String alias) {
        int property = propertyIndex(alias); return property < 0 ? null : LONG_NAMES[property];
    }
    static boolean isDeprecated(String alias) {
        int property = propertyIndex(alias); return property >= 0 && DEPRECATED[property];
    }
    static boolean isContributory(String alias) {
        int property = propertyIndex(alias); return property >= 0 && CONTRIBUTORY[property];
    }
    static boolean isNewInUnicode17(String alias) {
        int property = propertyIndex(alias); return property >= 0 && NEW_IN_UNICODE_17[property];
    }
    static String[] canonicalNames() { return LONG_NAMES.clone(); }

    private static int propertyIndex(String alias) {
        String name = looseName(alias); if (name == null) return -1;
        for (int i = 0; i < PROPERTY_ALIASES.length; i++)
            if (PROPERTY_ALIASES[i].equals(name)) return PROPERTY_ALIAS_INDEX[i];
        return -1;
    }
    private static Boolean binaryValue(String alias) {
        String value = looseName(alias); if (value == null) return null;
        if (value.equals("y") || value.equals("yes") || value.equals("t") || value.equals("true")) return true;
        if (value.equals("n") || value.equals("no") || value.equals("f") || value.equals("false")) return false;
        return null;
    }
    private static String looseName(String name) {
        if (name == null) return null;
        StringBuilder loose = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '-' || Character.isWhitespace(c)) continue;
            loose.append(Character.toLowerCase(c));
        }
        return loose.toString();
    }
    private static UnicodeSet[] buildYesSets() {
        UnicodeSet[] sets = new UnicodeSet[YES_RANGES.length];
        for (int p = 0; p < sets.length; p++) {
            sets[p] = new UnicodeSet();
            for (int i = 0; i < YES_RANGES[p].length; i += 2)
                sets[p].add(YES_RANGES[p][i], YES_RANGES[p][i + 1]);
            sets[p].freeze();
        }
        return sets;
    }
    private PerlUnicodeSpecializedBinaryData() {}
}
