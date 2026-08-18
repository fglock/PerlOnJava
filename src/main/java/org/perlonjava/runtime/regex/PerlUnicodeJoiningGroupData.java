/*
 * Generated from Perl 5.44's pinned Unicode Character Database. Do not edit manually.
 *
 * Source: DerivedJoiningGroup-17.0.0.txt
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

final class PerlUnicodeJoiningGroupData {
    static final String UNICODE_VERSION = "17.0.0";
    static final String DJOIN_GROUP_SHA256 = "bb67e0c00b88acfa5be633967b66b23326844a86e49c6fde7b57960d3af66cae";
    static final String PROP_VALUE_ALIASES_SHA256 = "670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01";
    static final String PROPERTY_ALIASES_SHA256 = "4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb";

    private static final String[] SHORT_VALUES = {
        "African_Feh", "African_Noon", "African_Qaf", "Ain", "Alaph", "Alef", "Beh", "Beth", "Burushaski_Yeh_Barree", "Dal", "Dalath_Rish", "E", "Farsi_Yeh", "Fe", "Feh", "Final_Semkath", "Gaf", "Gamal", "Hah", "Hanifi_Rohingya_Kinna_Ya", "Hanifi_Rohingya_Pa", "He", "Heh", "Heh_Goal", "Heth", "Kaf", "Kaph", "Kashmiri_Yeh", "Khaph", "Knotted_Heh", "Lam", "Lamadh", "Malayalam_Bha", "Malayalam_Ja", "Malayalam_Lla", "Malayalam_Llla", "Malayalam_Nga", "Malayalam_Nna", "Malayalam_Nnna", "Malayalam_Nya", "Malayalam_Ra", "Malayalam_Ssa", "Malayalam_Tta", "Manichaean_Aleph", "Manichaean_Ayin", "Manichaean_Beth", "Manichaean_Daleth", "Manichaean_Dhamedh", "Manichaean_Five", "Manichaean_Gimel", "Manichaean_Heth", "Manichaean_Hundred", "Manichaean_Kaph", "Manichaean_Lamedh", "Manichaean_Mem", "Manichaean_Nun", "Manichaean_One", "Manichaean_Pe", "Manichaean_Qoph", "Manichaean_Resh", "Manichaean_Sadhe", "Manichaean_Samekh", "Manichaean_Taw", "Manichaean_Ten", "Manichaean_Teth", "Manichaean_Thamedh", "Manichaean_Twenty", "Manichaean_Waw", "Manichaean_Yodh", "Manichaean_Zayin", "Meem", "Mim", "No_Joining_Group", "Noon", "Nun", "Nya", "Pe", "Qaf", "Qaph", "Reh", "Reversed_Pe", "Rohingya_Yeh", "Sad", "Sadhe", "Seen", "Semkath", "Shin", "Straight_Waw", "Swash_Kaf", "Syriac_Waw", "Tah", "Taw", "Teh_Marbuta", "Teh_Marbuta_Goal", "Teth", "Thin_Noon", "Thin_Yeh", "Vertical_Tail", "Waw", "Yeh", "Yeh_Barree", "Yeh_With_Tail", "Yudh", "Yudh_He", "Zain", "Zhain"
    };
    private static final String[] LONG_VALUES = {
        "African_Feh", "African_Noon", "African_Qaf", "Ain", "Alaph", "Alef", "Beh", "Beth", "Burushaski_Yeh_Barree", "Dal", "Dalath_Rish", "E", "Farsi_Yeh", "Fe", "Feh", "Final_Semkath", "Gaf", "Gamal", "Hah", "Hanifi_Rohingya_Kinna_Ya", "Hanifi_Rohingya_Pa", "He", "Heh", "Heh_Goal", "Heth", "Kaf", "Kaph", "Kashmiri_Yeh", "Khaph", "Knotted_Heh", "Lam", "Lamadh", "Malayalam_Bha", "Malayalam_Ja", "Malayalam_Lla", "Malayalam_Llla", "Malayalam_Nga", "Malayalam_Nna", "Malayalam_Nnna", "Malayalam_Nya", "Malayalam_Ra", "Malayalam_Ssa", "Malayalam_Tta", "Manichaean_Aleph", "Manichaean_Ayin", "Manichaean_Beth", "Manichaean_Daleth", "Manichaean_Dhamedh", "Manichaean_Five", "Manichaean_Gimel", "Manichaean_Heth", "Manichaean_Hundred", "Manichaean_Kaph", "Manichaean_Lamedh", "Manichaean_Mem", "Manichaean_Nun", "Manichaean_One", "Manichaean_Pe", "Manichaean_Qoph", "Manichaean_Resh", "Manichaean_Sadhe", "Manichaean_Samekh", "Manichaean_Taw", "Manichaean_Ten", "Manichaean_Teth", "Manichaean_Thamedh", "Manichaean_Twenty", "Manichaean_Waw", "Manichaean_Yodh", "Manichaean_Zayin", "Meem", "Mim", "No_Joining_Group", "Noon", "Nun", "Nya", "Pe", "Qaf", "Qaph", "Reh", "Reversed_Pe", "Rohingya_Yeh", "Sad", "Sadhe", "Seen", "Semkath", "Shin", "Straight_Waw", "Swash_Kaf", "Syriac_Waw", "Tah", "Taw", "Teh_Marbuta", "Teh_Marbuta_Goal", "Teth", "Thin_Noon", "Thin_Yeh", "Vertical_Tail", "Waw", "Yeh", "Yeh_Barree", "Yeh_With_Tail", "Yudh", "Yudh_He", "Zain", "Zhain"
    };
    private static final String[] VALUE_ALIASES = {
        "africanfeh", "africannoon", "africanqaf", "ain", "alaph", "alef", "beh", "beth", "burushaskiyehbarree", "dal", "dalathrish", "e", "farsiyeh", "fe", "feh", "finalsemkath", "gaf", "gamal", "hah", "hamzaonhehgoal", "hanifirohingyakinnaya", "hanifirohingyapa", "he", "heh", "hehgoal", "heth", "kaf", "kaph", "kashmiriyeh", "khaph", "knottedheh", "lam", "lamadh", "malayalambha", "malayalamja", "malayalamlla", "malayalamllla", "malayalamnga", "malayalamnna", "malayalamnnna", "malayalamnya", "malayalamra", "malayalamssa", "malayalamtta", "manichaeanaleph", "manichaeanayin", "manichaeanbeth", "manichaeandaleth", "manichaeandhamedh", "manichaeanfive", "manichaeangimel", "manichaeanheth", "manichaeanhundred", "manichaeankaph", "manichaeanlamedh", "manichaeanmem", "manichaeannun", "manichaeanone", "manichaeanpe", "manichaeanqoph", "manichaeanresh", "manichaeansadhe", "manichaeansamekh", "manichaeantaw", "manichaeanten", "manichaeanteth", "manichaeanthamedh", "manichaeantwenty", "manichaeanwaw", "manichaeanyodh", "manichaeanzayin", "meem", "mim", "nojoininggroup", "noon", "nun", "nya", "pe", "qaf", "qaph", "reh", "reversedpe", "rohingyayeh", "sad", "sadhe", "seen", "semkath", "shin", "straightwaw", "swashkaf", "syriacwaw", "tah", "taw", "tehmarbuta", "tehmarbutagoal", "teth", "thinnoon", "thinyeh", "verticaltail", "waw", "yeh", "yehbarree", "yehwithtail", "yudh", "yudhhe", "zain", "zhain"
    };
    private static final byte[] VALUE_ALIAS_INDEX = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 93, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105
    };
    private static final String[] WILDCARD_VALUES = {
        "African_Feh", "African_Noon", "African_Qaf", "Ain", "Alaph", "Alef", "Beh", "Beth", "Burushaski_Yeh_Barree", "Dal", "Dalath_Rish", "E", "Farsi_Yeh", "Fe", "Feh", "Final_Semkath", "Gaf", "Gamal", "Hah", "Hamza_On_Heh_Goal", "Hanifi_Rohingya_Kinna_Ya", "Hanifi_Rohingya_Pa", "He", "Heh", "Heh_Goal", "Heth", "Kaf", "Kaph", "Kashmiri_Yeh", "Khaph", "Knotted_Heh", "Lam", "Lamadh", "Malayalam_Bha", "Malayalam_Ja", "Malayalam_Lla", "Malayalam_Llla", "Malayalam_Nga", "Malayalam_Nna", "Malayalam_Nnna", "Malayalam_Nya", "Malayalam_Ra", "Malayalam_Ssa", "Malayalam_Tta", "Manichaean_Aleph", "Manichaean_Ayin", "Manichaean_Beth", "Manichaean_Daleth", "Manichaean_Dhamedh", "Manichaean_Five", "Manichaean_Gimel", "Manichaean_Heth", "Manichaean_Hundred", "Manichaean_Kaph", "Manichaean_Lamedh", "Manichaean_Mem", "Manichaean_Nun", "Manichaean_One", "Manichaean_Pe", "Manichaean_Qoph", "Manichaean_Resh", "Manichaean_Sadhe", "Manichaean_Samekh", "Manichaean_Taw", "Manichaean_Ten", "Manichaean_Teth", "Manichaean_Thamedh", "Manichaean_Twenty", "Manichaean_Waw", "Manichaean_Yodh", "Manichaean_Zayin", "Meem", "Mim", "No_Joining_Group", "Noon", "Nun", "Nya", "Pe", "Qaf", "Qaph", "Reh", "Reversed_Pe", "Rohingya_Yeh", "Sad", "Sadhe", "Seen", "Semkath", "Shin", "Straight_Waw", "Swash_Kaf", "Syriac_Waw", "Tah", "Taw", "Teh_Marbuta", "Teh_Marbuta_Goal", "Teth", "Thin_Noon", "Thin_Yeh", "Vertical_Tail", "Waw", "Yeh", "Yeh_Barree", "Yeh_With_Tail", "Yudh", "Yudh_He", "Zain", "Zhain"
    };
    private static final String[] PROPERTY_ALIASES = {
        "jg", "joininggroup"
    };

    private static final int[] RANGES = {
        0x0, 0x61F, 72, 0x620, 0x620, 27, 0x621, 0x621, 72, 0x622, 0x623, 5,
        0x624, 0x624, 98, 0x625, 0x625, 5, 0x626, 0x626, 99, 0x627, 0x627, 5,
        0x628, 0x628, 6, 0x629, 0x629, 92, 0x62A, 0x62B, 6, 0x62C, 0x62E, 18,
        0x62F, 0x630, 9, 0x631, 0x632, 79, 0x633, 0x634, 84, 0x635, 0x636, 82,
        0x637, 0x638, 90, 0x639, 0x63A, 3, 0x63B, 0x63C, 16, 0x63D, 0x63F, 12,
        0x640, 0x640, 72, 0x641, 0x641, 14, 0x642, 0x642, 77, 0x643, 0x643, 25,
        0x644, 0x644, 30, 0x645, 0x645, 70, 0x646, 0x646, 73, 0x647, 0x647, 22,
        0x648, 0x648, 98, 0x649, 0x64A, 99, 0x64B, 0x66D, 72, 0x66E, 0x66E, 6,
        0x66F, 0x66F, 77, 0x670, 0x670, 72, 0x671, 0x673, 5, 0x674, 0x674, 72,
        0x675, 0x675, 5, 0x676, 0x677, 98, 0x678, 0x678, 99, 0x679, 0x680, 6,
        0x681, 0x687, 18, 0x688, 0x690, 9, 0x691, 0x699, 79, 0x69A, 0x69C, 84,
        0x69D, 0x69E, 82, 0x69F, 0x69F, 90, 0x6A0, 0x6A0, 3, 0x6A1, 0x6A6, 14,
        0x6A7, 0x6A8, 77, 0x6A9, 0x6A9, 16, 0x6AA, 0x6AA, 88, 0x6AB, 0x6AB, 16,
        0x6AC, 0x6AE, 25, 0x6AF, 0x6B4, 16, 0x6B5, 0x6B8, 30, 0x6B9, 0x6BC, 73,
        0x6BD, 0x6BD, 75, 0x6BE, 0x6BE, 29, 0x6BF, 0x6BF, 18, 0x6C0, 0x6C0, 92,
        0x6C1, 0x6C2, 23, 0x6C3, 0x6C3, 93, 0x6C4, 0x6CB, 98, 0x6CC, 0x6CC, 12,
        0x6CD, 0x6CD, 101, 0x6CE, 0x6CE, 12, 0x6CF, 0x6CF, 98, 0x6D0, 0x6D1, 99,
        0x6D2, 0x6D3, 100, 0x6D4, 0x6D4, 72, 0x6D5, 0x6D5, 92, 0x6D6, 0x6ED, 72,
        0x6EE, 0x6EE, 9, 0x6EF, 0x6EF, 79, 0x6F0, 0x6F9, 72, 0x6FA, 0x6FA, 84,
        0x6FB, 0x6FB, 82, 0x6FC, 0x6FC, 3, 0x6FD, 0x6FE, 72, 0x6FF, 0x6FF, 29,
        0x700, 0x70F, 72, 0x710, 0x710, 4, 0x711, 0x711, 72, 0x712, 0x712, 7,
        0x713, 0x714, 17, 0x715, 0x716, 10, 0x717, 0x717, 21, 0x718, 0x718, 89,
        0x719, 0x719, 104, 0x71A, 0x71A, 24, 0x71B, 0x71C, 94, 0x71D, 0x71D, 102,
        0x71E, 0x71E, 103, 0x71F, 0x71F, 26, 0x720, 0x720, 31, 0x721, 0x721, 71,
        0x722, 0x722, 74, 0x723, 0x723, 85, 0x724, 0x724, 15, 0x725, 0x725, 11,
        0x726, 0x726, 76, 0x727, 0x727, 80, 0x728, 0x728, 83, 0x729, 0x729, 78,
        0x72A, 0x72A, 10, 0x72B, 0x72B, 86, 0x72C, 0x72C, 91, 0x72D, 0x72D, 7,
        0x72E, 0x72E, 17, 0x72F, 0x72F, 10, 0x730, 0x74C, 72, 0x74D, 0x74D, 105,
        0x74E, 0x74E, 28, 0x74F, 0x74F, 13, 0x750, 0x756, 6, 0x757, 0x758, 18,
        0x759, 0x75A, 9, 0x75B, 0x75B, 79, 0x75C, 0x75C, 84, 0x75D, 0x75F, 3,
        0x760, 0x761, 14, 0x762, 0x764, 16, 0x765, 0x766, 70, 0x767, 0x769, 73,
        0x76A, 0x76A, 30, 0x76B, 0x76C, 79, 0x76D, 0x76D, 84, 0x76E, 0x76F, 18,
        0x770, 0x770, 84, 0x771, 0x771, 79, 0x772, 0x772, 18, 0x773, 0x774, 5,
        0x775, 0x776, 12, 0x777, 0x777, 99, 0x778, 0x779, 98, 0x77A, 0x77B, 8,
        0x77C, 0x77C, 18, 0x77D, 0x77E, 84, 0x77F, 0x77F, 25, 0x780, 0x85F, 72,
        0x860, 0x860, 36, 0x861, 0x861, 33, 0x862, 0x862, 39, 0x863, 0x863, 42,
        0x864, 0x864, 37, 0x865, 0x865, 38, 0x866, 0x866, 32, 0x867, 0x867, 40,
        0x868, 0x868, 34, 0x869, 0x869, 35, 0x86A, 0x86A, 41, 0x86B, 0x86F, 72,
        0x870, 0x882, 5, 0x883, 0x885, 72, 0x886, 0x886, 96, 0x887, 0x888, 72,
        0x889, 0x889, 73, 0x88A, 0x88A, 18, 0x88B, 0x88C, 90, 0x88D, 0x88D, 16,
        0x88E, 0x88E, 97, 0x88F, 0x88F, 73, 0x890, 0x89F, 72, 0x8A0, 0x8A1, 6,
        0x8A2, 0x8A2, 18, 0x8A3, 0x8A3, 90, 0x8A4, 0x8A4, 14, 0x8A5, 0x8A5, 77,
        0x8A6, 0x8A6, 30, 0x8A7, 0x8A7, 70, 0x8A8, 0x8A9, 99, 0x8AA, 0x8AA, 79,
        0x8AB, 0x8AB, 98, 0x8AC, 0x8AC, 81, 0x8AD, 0x8AD, 72, 0x8AE, 0x8AE, 9,
        0x8AF, 0x8AF, 82, 0x8B0, 0x8B0, 16, 0x8B1, 0x8B1, 87, 0x8B2, 0x8B2, 79,
        0x8B3, 0x8B3, 3, 0x8B4, 0x8B4, 25, 0x8B5, 0x8B5, 77, 0x8B6, 0x8B8, 6,
        0x8B9, 0x8B9, 79, 0x8BA, 0x8BA, 99, 0x8BB, 0x8BB, 0, 0x8BC, 0x8BC, 2,
        0x8BD, 0x8BD, 1, 0x8BE, 0x8C0, 6, 0x8C1, 0x8C1, 18, 0x8C2, 0x8C2, 16,
        0x8C3, 0x8C3, 3, 0x8C4, 0x8C4, 2, 0x8C5, 0x8C6, 18, 0x8C7, 0x8C7, 30,
        0x8C8, 0x8C8, 16, 0x8C9, 0x10ABF, 72, 0x10AC0, 0x10AC0, 43, 0x10AC1, 0x10AC2, 45,
        0x10AC3, 0x10AC4, 49, 0x10AC5, 0x10AC5, 46, 0x10AC6, 0x10AC6, 72, 0x10AC7, 0x10AC7, 67,
        0x10AC8, 0x10AC8, 72, 0x10AC9, 0x10ACA, 69, 0x10ACB, 0x10ACC, 72, 0x10ACD, 0x10ACD, 50,
        0x10ACE, 0x10ACE, 64, 0x10ACF, 0x10ACF, 68, 0x10AD0, 0x10AD2, 52, 0x10AD3, 0x10AD3, 53,
        0x10AD4, 0x10AD4, 47, 0x10AD5, 0x10AD5, 65, 0x10AD6, 0x10AD6, 54, 0x10AD7, 0x10AD7, 55,
        0x10AD8, 0x10AD8, 61, 0x10AD9, 0x10ADA, 44, 0x10ADB, 0x10ADC, 57, 0x10ADD, 0x10ADD, 60,
        0x10ADE, 0x10AE0, 58, 0x10AE1, 0x10AE1, 59, 0x10AE2, 0x10AE3, 72, 0x10AE4, 0x10AE4, 62,
        0x10AE5, 0x10AEA, 72, 0x10AEB, 0x10AEB, 56, 0x10AEC, 0x10AEC, 48, 0x10AED, 0x10AED, 63,
        0x10AEE, 0x10AEE, 66, 0x10AEF, 0x10AEF, 51, 0x10AF0, 0x10D01, 72, 0x10D02, 0x10D02, 20,
        0x10D03, 0x10D08, 72, 0x10D09, 0x10D09, 20, 0x10D0A, 0x10D18, 72, 0x10D19, 0x10D19, 19,
        0x10D1A, 0x10D1B, 72, 0x10D1C, 0x10D1C, 20, 0x10D1D, 0x10D1D, 72, 0x10D1E, 0x10D1E, 19,
        0x10D1F, 0x10D1F, 72, 0x10D20, 0x10D20, 19, 0x10D21, 0x10D22, 72, 0x10D23, 0x10D23, 19,
        0x10D24, 0x10EC1, 72, 0x10EC2, 0x10EC2, 9, 0x10EC3, 0x10EC3, 90, 0x10EC4, 0x10EC4, 25,
        0x10EC5, 0x10EC5, 72, 0x10EC6, 0x10EC6, 95, 0x10EC7, 0x10EC7, 99, 0x10EC8, 0x10FFFF, 72,
    };

    private static final UnicodeSet[] VALUE_SETS = buildValueSets();

    static boolean isPropertyAlias(String alias) {
        String loose = looseName(alias);
        if (loose == null) return false;
        for (String candidate : PROPERTY_ALIASES) {
            if (candidate.equals(loose)) return true;
        }
        return false;
    }

    static UnicodeSet valueSet(String alias) {
        int index = valueIndex(alias);
        return index < 0 ? null : VALUE_SETS[index];
    }

    static String shortValue(String alias) {
        int index = valueIndex(alias);
        return index < 0 ? null : SHORT_VALUES[index];
    }

    static String canonicalValue(String alias) {
        int index = valueIndex(alias);
        return index < 0 ? null : LONG_VALUES[index];
    }

    static String[] canonicalValues() {
        return LONG_VALUES.clone();
    }

    static String[] wildcardValues() {
        return WILDCARD_VALUES.clone();
    }

    private static int valueIndex(String alias) {
        String loose = looseName(alias);
        if (loose == null) return -1;
        for (int i = 0; i < VALUE_ALIASES.length; i++) {
            if (VALUE_ALIASES[i].equals(loose)) return VALUE_ALIAS_INDEX[i];
        }
        return -1;
    }

    private static String looseName(String name) {
        if (name == null) return null;
        StringBuilder loose = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (character == '_' || character == '-' || Character.isWhitespace(character)) continue;
            loose.append(Character.toLowerCase(character));
        }
        return loose.toString();
    }

    private static UnicodeSet[] buildValueSets() {
        UnicodeSet[] sets = new UnicodeSet[LONG_VALUES.length];
        for (int i = 0; i < sets.length; i++) sets[i] = new UnicodeSet();
        for (int i = 0; i < RANGES.length; i += 3) {
            sets[RANGES[i + 2]].add(RANGES[i], RANGES[i + 1]);
        }
        for (UnicodeSet set : sets) set.freeze();
        return sets;
    }

    private PerlUnicodeJoiningGroupData() {
    }
}
