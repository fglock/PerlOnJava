/*
 * Generated from Perl 5.44's Unicode 17.0.0 Character Database.
 * Do not edit manually; run
 * dev/tools/generate_perl_unicode_quick_check_hangul_data.pl.
 *
 * Unicode data source copyright:
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in
 * the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;
import java.util.Arrays;
import java.util.Base64;

final class PerlUnicodeQuickCheckHangulData {
    static final String UNICODE_VERSION = "17.0.0";
    static final String PROPERTY_ALIASES_SHA256 = "4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb";
    static final String VALUE_ALIASES_SHA256 = "670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01";
    static final String HANGUL_SYLLABLE_TYPE_SHA256 = "5a57450afde0d082bc5026f7458649eac3b615490cc7e3d916b0367f1593c0e3";
    static final String NORMALIZATION_PROPS_SHA256 = "71fd6a206a2c0cdd41feb6b7f656aa31091db45e9cedc926985d718397f9e488";

    private static final String[] PROPERTY_SHORT_NAMES = {"hst", "NFC_QC", "NFD_QC", "NFKC_QC", "NFKD_QC"};
    private static final String[] PROPERTY_NAMES = {"Hangul_Syllable_Type", "NFC_Quick_Check", "NFD_Quick_Check", "NFKC_Quick_Check", "NFKD_Quick_Check"};
    private static final int[] RANGE_COUNTS = {810, 242, 485, 607, 817};

    private static final String[] SHORT_VALUES_0 = {
        "L", "LV", "LVT", "NA", "T", "V"
    };
    private static final String[] VALUES_0 = {
        "Leading_Jamo", "LV_Syllable", "LVT_Syllable", "Not_Applicable", "Trailing_Jamo", "Vowel_Jamo"
    };
    private static final String[] ALIAS_KEYS_0 = {
        "l", "leadingjamo", "lv", "lvsyllable", "lvt", "lvtsyllable", "na", "notapplicable", "t", "trailingjamo", "v", "voweljamo"
    };
    private static final byte[] ALIAS_IDS_0 = {
        0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5
    };
    private static final int[] CARDINALITIES_0 = {
        125, 399, 10773, 1102583, 137, 95
    };
    private static final String RANGE_DATA_0 =
        "/yEDXwBHBVcE364CAxwAggUDAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIA" +
        "ARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoC" +
        "AAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEa" +
        "AgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgAB" +
        "GgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIA" +
        "ARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoC" +
        "AAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEa" +
        "AgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgAB" +
        "GgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIA" +
        "ARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoC" +
        "AAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEa" +
        "AgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgAB" +
        "GgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIA" +
        "ARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoC" +
        "AAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEa" +
        "AgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgAB" +
        "GgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIA" +
        "ARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoC" +
        "AAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEa" +
        "AgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgAB" +
        "GgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIA" +
        "ARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCAAEaAgABGgIAARoCCwMWBQMDMASD0EAD";

    private static final String[] SHORT_VALUES_1 = {
        "M", "N", "Y"
    };
    private static final String[] VALUES_1 = {
        "Maybe", "No", "Yes"
    };
    private static final String[] ALIAS_KEYS_1 = {
        "m", "maybe", "n", "no", "y", "yes"
    };
    private static final byte[] ALIAS_IDS_1 = {
        0, 0, 1, 1, 2, 2
    };
    private static final int[] CARDINALITIES_1 = {
        132, 1120, 1112860
    };
    private static final String RANGE_DATA_1 =
        "/wUCBAAAAgYAAQIAAAACAAAAAgEABQIAAAYCBQADAgEAAAIBAAUCAAAGAgEBAAABAQAALQIAAQgCAAEHAgABygUCAgDlBQIAABoC" +
        "BwFdAgAAFwIAAAMCAQEAAgABUgIAAQECAAEhAgIBAQIAAd4BAgAAFgIBAAMCAQFfAgAAFwIAAH0CAABqAgAAEQIBAGYCAAAXAgAA" +
        "cQIAAAMCAAAOAgAA4gICAAEIAgABAwIAAQMCAAEDAgABCwIAAQgCAAEAAgEBAAIAAQcCAAEQAgABCAIAAQMCAAEDAgABAwIAAQsC" +
        "AAFzAgAAsQICFAAxAhoA8RICAAC6CAIAAQACAAEAAgABAAIAAQACAAEAAgABAAIAATwCAAEBAgABCQIAAQACAAEGAgABBgIAAQYC" +
        "AAEGAgABAQIBAQgCAAEAAgABAAIAAQECAQGjAgIAAQICAQH8AwIBAbAPAgABuwsCAQDkkAMCjQIBAQIAAQACAAEBAgkBAAIAAQAC" +
        "AAEBAgEBAgJDAQECaQFCAgABAAIAAQkCDAEAAgQBAAIAAQACAQEAAgEBAAIIAeoqAgAAawIAAJUEAgAAFwIAAF8CAAABAgAABQIA" +
        "AAECAAAAAgIA5QECAAAIAgAAAQIAAPABAgAA/wYCAADsjwECCwC8GAIBAPTHAQIGAVUCBQG+zAQCnQQB4Ys4Ag==";

    private static final String[] SHORT_VALUES_2 = {
        "N", "Y"
    };
    private static final String[] VALUES_2 = {
        "No", "Yes"
    };
    private static final String[] ALIAS_KEYS_2 = {
        "n", "no", "y", "yes"
    };
    private static final byte[] ALIAS_IDS_2 = {
        0, 0, 1, 1
    };
    private static final int[] CARDINALITIES_2 = {
        13253, 1100859
    };
    private static final String RANGE_DATA_2 =
        "vwEBBQAAAQgAAAEFAAEBBAABAQUAAAEIAAABBQABAQQAAAEQAAEBEwABAQgAAgEDAAABBQADAQUAAgEFAAEBEQABARYAIAEBAAwB" +
        "AQAbAQ8AAAEFAAEBCgACAQEAAQEjAAEBAQAFAQ0AiwIBAQAAAQEALgEAAAgBAAAFAQUAAAEAAAABAgAYAQYAGAEEAAMBAQAqAQEA" +
        "AAEAAAIBAAADAQIACQEAAB4BAAAVAQEAAAEAAAIBAAADAQIAFgEBAEgBAQAMAQMAAQEBAAEBBQABAQUAAQELAAEBAQCnAgEEAJgB" +
        "AQAAAAEAAA8BAADUBAEAAAYBAAABAQAAIgEHAGoBAQAOAQEAAAEAAFIBAAABAQAAIQECAAEBAADoAQEAAAEBAQAOAQEANQEAADQB" +
        "AgB6AQAAdgEAAAUBAQAAAQEAfQECAIwBAQAAAAECAOMCAQAACAEAAAMBAAADAQAAAwEAAAsBAAAIAQAAAAEBAAABAAAHAQAAEAEA" +
        "AAgBAAADAQAAAwEAAAMBAAALAQAAawEAAN4VAQAAAAEAAAABAAAAAQAAAAEAAAIBAAAnAQAAAAEAAAEBAQAAAQAAuwUBmQEAAAEA" +
        "AAMBWQAFARUAAQEFAAEBJQABAQUAAQEHAAABAAAAAQAAAAEAAAABHgABATQAAAEGAAABAAABAQMAAAENAAEBBQAAARIAAQECAAAB" +
        "BwABAQEAowIBAAACAQEAbQEBABEBAAAdAQIAMwEAAAMBAAABAQAAFgEAAAABAAAZAQAAAQEAAAEBAAAAAQAAFQEAAAABAAAJAQQA" +
        "AQEBAAEBAQAFAQEAAQEBAAEBAQAhAQMALwEDAAUBAwA6AQEAsA8BAADuCgEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAA" +
        "AQAAAAEAAAABAAAAAQAAAQEAAAABAAAAAQAABQEBAAABAQAAAQEAAAEBAAABAQAVAQAACAEAAAwBAAAAAQAAAAEAAAABAAAAAQAA" +
        "AAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAEBAAAAAQAAAAEAAAUBAQAAAQEAAAEBAAABAQAAAQEAFQEAAAEBAwACAQAAgPYB" +
        "AaNXANtCAY0CAAEBAAAAAQAAAQEJAAABAAAAAQAAAQEBAAIBQwABAWkAQgEAAAABAAAJAQwAAAEEAAABAAAAAQEAAAEBAAABCAD5" +
        "FAEAABkBAAC0FQEAAAABAAANAQAAgQEBAQCaBAEBADUBAAAAAQAABwEAAAEBAAAyAQAAAAEBAPEBAQEAAAEAAPoBAQEA+wYBAADn" +
        "jwEBBwC+GAECAPLHAQEGAFUBBQC+zAQBnQQA4Ys4AQ==";

    private static final String[] SHORT_VALUES_3 = {
        "M", "N", "Y"
    };
    private static final String[] VALUES_3 = {
        "Maybe", "No", "Yes"
    };
    private static final String[] ALIAS_KEYS_3 = {
        "m", "maybe", "n", "no", "y", "yes"
    };
    private static final byte[] ALIAS_IDS_3 = {
        0, 0, 1, 1, 2, 2
    };
    private static final int[] CARDINALITIES_3 = {
        132, 4965, 1109015
    };
    private static final String RANGE_DATA_3 =
        "nwECAAEGAgABAAIAAQMCAAEBAgMBAQICAQACAgFyAgEBCgIBAQcCAAE0AgABQwIIASMCAgG7AQIIAR4CBQEBAgQBGgIEAAACBgAB" +
        "AgAAAAIAAAACAQAFAgAABgIFAAMCAQAAAgEABQIAAAYCAQEAAAEBAAAtAgABBAIAAQICAAEEAgEBAAIAAUcCBgEYAgIBAAIBAQIC" +
        "AAGMAwIAAcoBAgIAHgIDAcIFAgAAGgIHAV0CAAAXAgAAAwIBAQACAAFSAgABAQIAASECAgEBAgAB3gECAAAWAgEAAwIBAV8CAAAX" +
        "AgAAfQIAAGoCAAARAgEAZgIAABcCAABxAgAAAwIAAA4CAABSAgABfgIAAScCAQEtAgABNQIAAQgCAAEDAgABAwIAAQMCAAELAgAB" +
        "CAIAAQACBAEGAgABEAIAAQgCAAEDAgABAwIAAQMCAAELAgABcwIAAMwBAgABYwIUADECGgDxEgIAAPUDAgIBAAIKAQACEQEAAhsB" +
        "DAIAASECJAHZAQIBAdQBAgABAAIAAQACAAEAAgABAAIAAQACAAEAAgABPAIAAQACBAEGAgABAAIAAQACAgECAgABBgIAAQACAgEC" +
        "AgABBgIAAQACAgEIAgABAAIAAQACAQEAAgoBBQIAAQQCAAELAgIBBwIAAQICAQEAAgEBAwIAAQACAAEHAgIBDAIAAQYCAAEPAgEB" +
        "AQIaAQACDAEKAgABVgIDAQACAgEAAgoBAAIBAQECBAEBAgIBAAIAAQACAAEAAgABAAIDAQACAgEAAgYBAAIFAQMCBAEFAi8BCAIA" +
        "AaEBAgEBAAIBAfcBAgEBtAICigEBoAoCAAFmAgIBZAIAAZ4DAgEB8AECAAGuAgIAAVICAAELAtUBASkCAAE0AgABAAICAV0CAQAB" +
        "AQECAAFeAgABMAJdAQICDQFfAh4BAAInAQcCLgEAAv8CAZvlAQIBAdEBAgABfwIDAQICAQHhBgIDAQgCAAGVmwECjQIBAQIAAQAC" +
        "AAEBAgkBAAIAAQACAAEBAgEBAgJDAQECaQElAgYBCwIEAQQCAAEAAhcBAAIEAQACAAEAAgEBAAIBAQACawEgAuoCARECPwEBAjUB" +
        "JwIMARICCQEVAhQBAQILAQACEgEAAgMBAwICAQACAAEAAoYBAQMCvQEBAgIFAQECBQEBAgUBAQICAQICBgEAAgYBkQ8CBAEAAikB" +
        "AAIIAf4RAgAAawIAAJUEAgAAFwIAAF8CAAABAgAABQIAAAECAAAAAgIA5QECAAAIAgAAAQIAAPABAgAA/wYCAADsjwECCwC8GAIB" +
        "AOy+AQIjAeMIAgYBVQIFAb4EAlQBAAJGAQACAQEBAgABAQIBAQECAwEAAgsBAAIAAQACBgEAAkABAAIDAQECBwEAAgYBAAIbAQAC" +
        "AwEAAgQBAAIAAQICBgEAAtMCAQECowIBAQIxAa8QAj0BkRsCAwEAAhoBAAIBAQACAAEBAgABAAIJAQACAwEAAgABAAIAAQUCAAED" +
        "AgABAAIAAQACAAEAAgIBAAIBAQACAAEBAgABAAIAAQACAAEAAgABAAIAAQACAQEAAgABAQIDAQACBgEAAgMBAAIDAQACAAEAAgkB" +
        "AAIQAQQCAgEAAgQBAAIQAcMEAgoBBAIeAQACHwEZAgIBIgIAAW4CAgEMAisBAwIIAQYCAQGdEwIJAYX4AwKdBAHhizgC";

    private static final String[] SHORT_VALUES_4 = {
        "N", "Y"
    };
    private static final String[] VALUES_4 = {
        "No", "Yes"
    };
    private static final String[] ALIAS_KEYS_4 = {
        "n", "no", "y", "yes"
    };
    private static final byte[] ALIAS_IDS_4 = {
        0, 0, 1, 1
    };
    private static final int[] CARDINALITIES_4 = {
        17086, 1097026
    };
    private static final String RANGE_DATA_4 =
        "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";

    private static final String[][] SHORT_VALUES = {
        SHORT_VALUES_0, SHORT_VALUES_1, SHORT_VALUES_2, SHORT_VALUES_3, SHORT_VALUES_4
    };
    private static final String[][] VALUES = {
        VALUES_0, VALUES_1, VALUES_2, VALUES_3, VALUES_4
    };
    private static final String[][] ALIAS_KEYS = {
        ALIAS_KEYS_0, ALIAS_KEYS_1, ALIAS_KEYS_2, ALIAS_KEYS_3, ALIAS_KEYS_4
    };
    private static final byte[][] ALIAS_IDS = {
        ALIAS_IDS_0, ALIAS_IDS_1, ALIAS_IDS_2, ALIAS_IDS_3, ALIAS_IDS_4
    };
    private static final int[][] CARDINALITIES = {
        CARDINALITIES_0, CARDINALITIES_1, CARDINALITIES_2,
        CARDINALITIES_3, CARDINALITIES_4
    };
    private static final String[] RANGE_DATA = {
        RANGE_DATA_0, RANGE_DATA_1, RANGE_DATA_2, RANGE_DATA_3, RANGE_DATA_4
    };
    private static final UnicodeSet[][] SETS = buildSets();

    static boolean isPropertyAlias(String alias) {
        return propertyIndex(alias) >= 0;
    }

    static String canonicalProperty(String alias) {
        int property = propertyIndex(alias);
        return property < 0 ? null : PROPERTY_NAMES[property];
    }

    static UnicodeSet valueSet(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        int value = property < 0 ? -1 : valueIndex(property, valueAlias);
        return value < 0 ? null : SETS[property][value];
    }

    static String canonicalValue(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        int value = property < 0 ? -1 : valueIndex(property, valueAlias);
        return value < 0 ? null : VALUES[property][value];
    }

    static String shortValue(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        int value = property < 0 ? -1 : valueIndex(property, valueAlias);
        return value < 0 ? null : SHORT_VALUES[property][value];
    }

    static String[] canonicalValues(String propertyAlias) {
        int property = propertyIndex(propertyAlias);
        return property < 0 ? null : VALUES[property].clone();
    }

    static String[] wildcardValues(String propertyAlias) {
        return canonicalValues(propertyAlias);
    }

    static int rangeCount(String propertyAlias) {
        int property = propertyIndex(propertyAlias);
        return property < 0 ? -1 : RANGE_COUNTS[property];
    }

    static int expectedCardinality(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        int value = property < 0 ? -1 : valueIndex(property, valueAlias);
        return value < 0 ? -1 : CARDINALITIES[property][value];
    }

    private static int propertyIndex(String alias) {
        String key = loose(alias);
        if (key == null) return -1;
        for (int i = 0; i < PROPERTY_NAMES.length; i++) {
            if (key.equals(loose(PROPERTY_SHORT_NAMES[i]))
                    || key.equals(loose(PROPERTY_NAMES[i]))) return i;
        }
        return -1;
    }

    private static int valueIndex(int property, String alias) {
        String key = loose(alias);
        if (key == null) return -1;
        int index = Arrays.binarySearch(ALIAS_KEYS[property], key);
        return index < 0 ? -1 : ALIAS_IDS[property][index] & 0xff;
    }

    private static String loose(String value) {
        if (value == null) return null;
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '_' || c == '-' || (c >= '\t' && c <= '\r')) continue;
            if (c >= 'A' && c <= 'Z') c = (char) (c + ('a' - 'A'));
            result.append(c);
        }
        return result.toString();
    }

    private static UnicodeSet[][] buildSets() {
        UnicodeSet[][] sets = new UnicodeSet[RANGE_DATA.length][];
        for (int property = 0; property < RANGE_DATA.length; property++) {
            sets[property] = new UnicodeSet[VALUES[property].length];
            for (int value = 0; value < sets[property].length; value++) {
                sets[property][value] = new UnicodeSet();
            }
            byte[] data = Base64.getDecoder().decode(RANGE_DATA[property]);
            int[] cursor = {0};
            int start = 0;
            int ranges = 0;
            while (cursor[0] < data.length) {
                int end = start + readUleb128(data, cursor);
                int value = readUleb128(data, cursor);
                if (value < 0 || value >= sets[property].length || end > 0x10ffff) {
                    throw new IllegalStateException("Invalid generated Unicode range data");
                }
                sets[property][value].add(start, end);
                start = end + 1;
                ranges++;
            }
            if (start != 0x110000 || ranges != RANGE_COUNTS[property]) {
                throw new IllegalStateException("Incomplete generated Unicode partition");
            }
            for (UnicodeSet set : sets[property]) set.freeze();
        }
        return sets;
    }

    private static int readUleb128(byte[] data, int[] cursor) {
        int value = 0;
        int shift = 0;
        while (true) {
            if (cursor[0] >= data.length || shift > 28) {
                throw new IllegalStateException("Invalid generated ULEB128 data");
            }
            int next = data[cursor[0]++] & 0xff;
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
            shift += 7;
        }
    }

    private PerlUnicodeQuickCheckHangulData() {
    }
}
