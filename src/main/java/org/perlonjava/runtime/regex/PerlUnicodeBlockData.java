package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;
import java.util.Arrays;

/*
 * Generated from Perl 5.44's pinned Unicode Character Database by
 * dev/tools/generate_perl_unicode_block_data.pl. Do not edit manually.
 *
 * Unicode data source copyright:
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in
 * the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 */
final class PerlUnicodeBlockData {
    static final String UNICODE_VERSION = "17.0.0";
    static final short INVALID = -1;
    static final short NO_BLOCK = 0;

    private static final String[] VALUE_NAMES = {
        "No_Block", "Basic_Latin", "Latin_1_Supplement", "Latin_Extended_A", "Latin_Extended_B", "IPA_Extensions",
        "Spacing_Modifier_Letters", "Combining_Diacritical_Marks", "Greek_And_Coptic", "Cyrillic", "Cyrillic_Supplement", "Armenian",
        "Hebrew", "Arabic", "Syriac", "Arabic_Supplement", "Thaana", "NKo",
        "Samaritan", "Mandaic", "Syriac_Supplement", "Arabic_Extended_B", "Arabic_Extended_A", "Devanagari",
        "Bengali", "Gurmukhi", "Gujarati", "Oriya", "Tamil", "Telugu",
        "Kannada", "Malayalam", "Sinhala", "Thai", "Lao", "Tibetan",
        "Myanmar", "Georgian", "Hangul_Jamo", "Ethiopic", "Ethiopic_Supplement", "Cherokee",
        "Unified_Canadian_Aboriginal_Syllabics", "Ogham", "Runic", "Tagalog", "Hanunoo", "Buhid",
        "Tagbanwa", "Khmer", "Mongolian", "Unified_Canadian_Aboriginal_Syllabics_Extended", "Limbu", "Tai_Le",
        "New_Tai_Lue", "Khmer_Symbols", "Buginese", "Tai_Tham", "Combining_Diacritical_Marks_Extended", "Balinese",
        "Sundanese", "Batak", "Lepcha", "Ol_Chiki", "Cyrillic_Extended_C", "Georgian_Extended",
        "Sundanese_Supplement", "Vedic_Extensions", "Phonetic_Extensions", "Phonetic_Extensions_Supplement", "Combining_Diacritical_Marks_Supplement", "Latin_Extended_Additional",
        "Greek_Extended", "General_Punctuation", "Superscripts_And_Subscripts", "Currency_Symbols", "Combining_Diacritical_Marks_For_Symbols", "Letterlike_Symbols",
        "Number_Forms", "Arrows", "Mathematical_Operators", "Miscellaneous_Technical", "Control_Pictures", "Optical_Character_Recognition",
        "Enclosed_Alphanumerics", "Box_Drawing", "Block_Elements", "Geometric_Shapes", "Miscellaneous_Symbols", "Dingbats",
        "Miscellaneous_Mathematical_Symbols_A", "Supplemental_Arrows_A", "Braille_Patterns", "Supplemental_Arrows_B", "Miscellaneous_Mathematical_Symbols_B", "Supplemental_Mathematical_Operators",
        "Miscellaneous_Symbols_And_Arrows", "Glagolitic", "Latin_Extended_C", "Coptic", "Georgian_Supplement", "Tifinagh",
        "Ethiopic_Extended", "Cyrillic_Extended_A", "Supplemental_Punctuation", "CJK_Radicals_Supplement", "Kangxi_Radicals", "Ideographic_Description_Characters",
        "CJK_Symbols_And_Punctuation", "Hiragana", "Katakana", "Bopomofo", "Hangul_Compatibility_Jamo", "Kanbun",
        "Bopomofo_Extended", "CJK_Strokes", "Katakana_Phonetic_Extensions", "Enclosed_CJK_Letters_And_Months", "CJK_Compatibility", "CJK_Unified_Ideographs_Extension_A",
        "Yijing_Hexagram_Symbols", "CJK_Unified_Ideographs", "Yi_Syllables", "Yi_Radicals", "Lisu", "Vai",
        "Cyrillic_Extended_B", "Bamum", "Modifier_Tone_Letters", "Latin_Extended_D", "Syloti_Nagri", "Common_Indic_Number_Forms",
        "Phags_Pa", "Saurashtra", "Devanagari_Extended", "Kayah_Li", "Rejang", "Hangul_Jamo_Extended_A",
        "Javanese", "Myanmar_Extended_B", "Cham", "Myanmar_Extended_A", "Tai_Viet", "Meetei_Mayek_Extensions",
        "Ethiopic_Extended_A", "Latin_Extended_E", "Cherokee_Supplement", "Meetei_Mayek", "Hangul_Syllables", "Hangul_Jamo_Extended_B",
        "High_Surrogates", "High_Private_Use_Surrogates", "Low_Surrogates", "Private_Use_Area", "CJK_Compatibility_Ideographs", "Alphabetic_Presentation_Forms",
        "Arabic_Presentation_Forms_A", "Variation_Selectors", "Vertical_Forms", "Combining_Half_Marks", "CJK_Compatibility_Forms", "Small_Form_Variants",
        "Arabic_Presentation_Forms_B", "Halfwidth_And_Fullwidth_Forms", "Specials", "Linear_B_Syllabary", "Linear_B_Ideograms", "Aegean_Numbers",
        "Ancient_Greek_Numbers", "Ancient_Symbols", "Phaistos_Disc", "Lycian", "Carian", "Coptic_Epact_Numbers",
        "Old_Italic", "Gothic", "Old_Permic", "Ugaritic", "Old_Persian", "Deseret",
        "Shavian", "Osmanya", "Osage", "Elbasan", "Caucasian_Albanian", "Vithkuqi",
        "Todhri", "Linear_A", "Latin_Extended_F", "Cypriot_Syllabary", "Imperial_Aramaic", "Palmyrene",
        "Nabataean", "Hatran", "Phoenician", "Lydian", "Sidetic", "Meroitic_Hieroglyphs",
        "Meroitic_Cursive", "Kharoshthi", "Old_South_Arabian", "Old_North_Arabian", "Manichaean", "Avestan",
        "Inscriptional_Parthian", "Inscriptional_Pahlavi", "Psalter_Pahlavi", "Old_Turkic", "Old_Hungarian", "Hanifi_Rohingya",
        "Garay", "Rumi_Numeral_Symbols", "Yezidi", "Arabic_Extended_C", "Old_Sogdian", "Sogdian",
        "Old_Uyghur", "Chorasmian", "Elymaic", "Brahmi", "Kaithi", "Sora_Sompeng",
        "Chakma", "Mahajani", "Sharada", "Sinhala_Archaic_Numbers", "Khojki", "Multani",
        "Khudawadi", "Grantha", "Tulu_Tigalari", "Newa", "Tirhuta", "Siddham",
        "Modi", "Mongolian_Supplement", "Takri", "Myanmar_Extended_C", "Ahom", "Dogra",
        "Warang_Citi", "Dives_Akuru", "Nandinagari", "Zanabazar_Square", "Soyombo", "Unified_Canadian_Aboriginal_Syllabics_Extended_A",
        "Pau_Cin_Hau", "Devanagari_Extended_A", "Sharada_Supplement", "Sunuwar", "Bhaiksuki", "Marchen",
        "Masaram_Gondi", "Gunjala_Gondi", "Tolong_Siki", "Makasar", "Kawi", "Lisu_Supplement",
        "Tamil_Supplement", "Cuneiform", "Cuneiform_Numbers_And_Punctuation", "Early_Dynastic_Cuneiform", "Cypro_Minoan", "Egyptian_Hieroglyphs",
        "Egyptian_Hieroglyph_Format_Controls", "Egyptian_Hieroglyphs_Extended_A", "Anatolian_Hieroglyphs", "Gurung_Khema", "Bamum_Supplement", "Mro",
        "Tangsa", "Bassa_Vah", "Pahawh_Hmong", "Kirat_Rai", "Medefaidrin", "Beria_Erfe",
        "Miao", "Ideographic_Symbols_And_Punctuation", "Tangut", "Tangut_Components", "Khitan_Small_Script", "Tangut_Supplement",
        "Tangut_Components_Supplement", "Kana_Extended_B", "Kana_Supplement", "Kana_Extended_A", "Small_Kana_Extension", "Nushu",
        "Duployan", "Shorthand_Format_Controls", "Symbols_For_Legacy_Computing_Supplement", "Miscellaneous_Symbols_Supplement", "Znamenny_Musical_Notation", "Byzantine_Musical_Symbols",
        "Musical_Symbols", "Ancient_Greek_Musical_Notation", "Kaktovik_Numerals", "Mayan_Numerals", "Tai_Xuan_Jing_Symbols", "Counting_Rod_Numerals",
        "Mathematical_Alphanumeric_Symbols", "Sutton_SignWriting", "Latin_Extended_G", "Glagolitic_Supplement", "Cyrillic_Extended_D", "Nyiakeng_Puachue_Hmong",
        "Toto", "Wancho", "Nag_Mundari", "Ol_Onal", "Tai_Yo", "Ethiopic_Extended_B",
        "Mende_Kikakui", "Adlam", "Indic_Siyaq_Numbers", "Ottoman_Siyaq_Numbers", "Arabic_Mathematical_Alphabetic_Symbols", "Mahjong_Tiles",
        "Domino_Tiles", "Playing_Cards", "Enclosed_Alphanumeric_Supplement", "Enclosed_Ideographic_Supplement", "Miscellaneous_Symbols_And_Pictographs", "Emoticons",
        "Ornamental_Dingbats", "Transport_And_Map_Symbols", "Alchemical_Symbols", "Geometric_Shapes_Extended", "Supplemental_Arrows_C", "Supplemental_Symbols_And_Pictographs",
        "Chess_Symbols", "Symbols_And_Pictographs_Extended_A", "Symbols_For_Legacy_Computing", "CJK_Unified_Ideographs_Extension_B", "CJK_Unified_Ideographs_Extension_C", "CJK_Unified_Ideographs_Extension_D",
        "CJK_Unified_Ideographs_Extension_E", "CJK_Unified_Ideographs_Extension_F", "CJK_Unified_Ideographs_Extension_I", "CJK_Compatibility_Ideographs_Supplement", "CJK_Unified_Ideographs_Extension_G", "CJK_Unified_Ideographs_Extension_H",
        "CJK_Unified_Ideographs_Extension_J", "Tags", "Variation_Selectors_Supplement", "Supplementary_Private_Use_Area_A", "Supplementary_Private_Use_Area_B",
    };

    private static final int[] RANGES = {
        0x0, 0x7F, 1, 0x80, 0xFF, 2, 0x100, 0x17F, 3,
        0x180, 0x24F, 4, 0x250, 0x2AF, 5, 0x2B0, 0x2FF, 6,
        0x300, 0x36F, 7, 0x370, 0x3FF, 8, 0x400, 0x4FF, 9,
        0x500, 0x52F, 10, 0x530, 0x58F, 11, 0x590, 0x5FF, 12,
        0x600, 0x6FF, 13, 0x700, 0x74F, 14, 0x750, 0x77F, 15,
        0x780, 0x7BF, 16, 0x7C0, 0x7FF, 17, 0x800, 0x83F, 18,
        0x840, 0x85F, 19, 0x860, 0x86F, 20, 0x870, 0x89F, 21,
        0x8A0, 0x8FF, 22, 0x900, 0x97F, 23, 0x980, 0x9FF, 24,
        0xA00, 0xA7F, 25, 0xA80, 0xAFF, 26, 0xB00, 0xB7F, 27,
        0xB80, 0xBFF, 28, 0xC00, 0xC7F, 29, 0xC80, 0xCFF, 30,
        0xD00, 0xD7F, 31, 0xD80, 0xDFF, 32, 0xE00, 0xE7F, 33,
        0xE80, 0xEFF, 34, 0xF00, 0xFFF, 35, 0x1000, 0x109F, 36,
        0x10A0, 0x10FF, 37, 0x1100, 0x11FF, 38, 0x1200, 0x137F, 39,
        0x1380, 0x139F, 40, 0x13A0, 0x13FF, 41, 0x1400, 0x167F, 42,
        0x1680, 0x169F, 43, 0x16A0, 0x16FF, 44, 0x1700, 0x171F, 45,
        0x1720, 0x173F, 46, 0x1740, 0x175F, 47, 0x1760, 0x177F, 48,
        0x1780, 0x17FF, 49, 0x1800, 0x18AF, 50, 0x18B0, 0x18FF, 51,
        0x1900, 0x194F, 52, 0x1950, 0x197F, 53, 0x1980, 0x19DF, 54,
        0x19E0, 0x19FF, 55, 0x1A00, 0x1A1F, 56, 0x1A20, 0x1AAF, 57,
        0x1AB0, 0x1AFF, 58, 0x1B00, 0x1B7F, 59, 0x1B80, 0x1BBF, 60,
        0x1BC0, 0x1BFF, 61, 0x1C00, 0x1C4F, 62, 0x1C50, 0x1C7F, 63,
        0x1C80, 0x1C8F, 64, 0x1C90, 0x1CBF, 65, 0x1CC0, 0x1CCF, 66,
        0x1CD0, 0x1CFF, 67, 0x1D00, 0x1D7F, 68, 0x1D80, 0x1DBF, 69,
        0x1DC0, 0x1DFF, 70, 0x1E00, 0x1EFF, 71, 0x1F00, 0x1FFF, 72,
        0x2000, 0x206F, 73, 0x2070, 0x209F, 74, 0x20A0, 0x20CF, 75,
        0x20D0, 0x20FF, 76, 0x2100, 0x214F, 77, 0x2150, 0x218F, 78,
        0x2190, 0x21FF, 79, 0x2200, 0x22FF, 80, 0x2300, 0x23FF, 81,
        0x2400, 0x243F, 82, 0x2440, 0x245F, 83, 0x2460, 0x24FF, 84,
        0x2500, 0x257F, 85, 0x2580, 0x259F, 86, 0x25A0, 0x25FF, 87,
        0x2600, 0x26FF, 88, 0x2700, 0x27BF, 89, 0x27C0, 0x27EF, 90,
        0x27F0, 0x27FF, 91, 0x2800, 0x28FF, 92, 0x2900, 0x297F, 93,
        0x2980, 0x29FF, 94, 0x2A00, 0x2AFF, 95, 0x2B00, 0x2BFF, 96,
        0x2C00, 0x2C5F, 97, 0x2C60, 0x2C7F, 98, 0x2C80, 0x2CFF, 99,
        0x2D00, 0x2D2F, 100, 0x2D30, 0x2D7F, 101, 0x2D80, 0x2DDF, 102,
        0x2DE0, 0x2DFF, 103, 0x2E00, 0x2E7F, 104, 0x2E80, 0x2EFF, 105,
        0x2F00, 0x2FDF, 106, 0x2FE0, 0x2FEF, 0, 0x2FF0, 0x2FFF, 107,
        0x3000, 0x303F, 108, 0x3040, 0x309F, 109, 0x30A0, 0x30FF, 110,
        0x3100, 0x312F, 111, 0x3130, 0x318F, 112, 0x3190, 0x319F, 113,
        0x31A0, 0x31BF, 114, 0x31C0, 0x31EF, 115, 0x31F0, 0x31FF, 116,
        0x3200, 0x32FF, 117, 0x3300, 0x33FF, 118, 0x3400, 0x4DBF, 119,
        0x4DC0, 0x4DFF, 120, 0x4E00, 0x9FFF, 121, 0xA000, 0xA48F, 122,
        0xA490, 0xA4CF, 123, 0xA4D0, 0xA4FF, 124, 0xA500, 0xA63F, 125,
        0xA640, 0xA69F, 126, 0xA6A0, 0xA6FF, 127, 0xA700, 0xA71F, 128,
        0xA720, 0xA7FF, 129, 0xA800, 0xA82F, 130, 0xA830, 0xA83F, 131,
        0xA840, 0xA87F, 132, 0xA880, 0xA8DF, 133, 0xA8E0, 0xA8FF, 134,
        0xA900, 0xA92F, 135, 0xA930, 0xA95F, 136, 0xA960, 0xA97F, 137,
        0xA980, 0xA9DF, 138, 0xA9E0, 0xA9FF, 139, 0xAA00, 0xAA5F, 140,
        0xAA60, 0xAA7F, 141, 0xAA80, 0xAADF, 142, 0xAAE0, 0xAAFF, 143,
        0xAB00, 0xAB2F, 144, 0xAB30, 0xAB6F, 145, 0xAB70, 0xABBF, 146,
        0xABC0, 0xABFF, 147, 0xAC00, 0xD7AF, 148, 0xD7B0, 0xD7FF, 149,
        0xD800, 0xDB7F, 150, 0xDB80, 0xDBFF, 151, 0xDC00, 0xDFFF, 152,
        0xE000, 0xF8FF, 153, 0xF900, 0xFAFF, 154, 0xFB00, 0xFB4F, 155,
        0xFB50, 0xFDFF, 156, 0xFE00, 0xFE0F, 157, 0xFE10, 0xFE1F, 158,
        0xFE20, 0xFE2F, 159, 0xFE30, 0xFE4F, 160, 0xFE50, 0xFE6F, 161,
        0xFE70, 0xFEFF, 162, 0xFF00, 0xFFEF, 163, 0xFFF0, 0xFFFF, 164,
        0x10000, 0x1007F, 165, 0x10080, 0x100FF, 166, 0x10100, 0x1013F, 167,
        0x10140, 0x1018F, 168, 0x10190, 0x101CF, 169, 0x101D0, 0x101FF, 170,
        0x10200, 0x1027F, 0, 0x10280, 0x1029F, 171, 0x102A0, 0x102DF, 172,
        0x102E0, 0x102FF, 173, 0x10300, 0x1032F, 174, 0x10330, 0x1034F, 175,
        0x10350, 0x1037F, 176, 0x10380, 0x1039F, 177, 0x103A0, 0x103DF, 178,
        0x103E0, 0x103FF, 0, 0x10400, 0x1044F, 179, 0x10450, 0x1047F, 180,
        0x10480, 0x104AF, 181, 0x104B0, 0x104FF, 182, 0x10500, 0x1052F, 183,
        0x10530, 0x1056F, 184, 0x10570, 0x105BF, 185, 0x105C0, 0x105FF, 186,
        0x10600, 0x1077F, 187, 0x10780, 0x107BF, 188, 0x107C0, 0x107FF, 0,
        0x10800, 0x1083F, 189, 0x10840, 0x1085F, 190, 0x10860, 0x1087F, 191,
        0x10880, 0x108AF, 192, 0x108B0, 0x108DF, 0, 0x108E0, 0x108FF, 193,
        0x10900, 0x1091F, 194, 0x10920, 0x1093F, 195, 0x10940, 0x1095F, 196,
        0x10960, 0x1097F, 0, 0x10980, 0x1099F, 197, 0x109A0, 0x109FF, 198,
        0x10A00, 0x10A5F, 199, 0x10A60, 0x10A7F, 200, 0x10A80, 0x10A9F, 201,
        0x10AA0, 0x10ABF, 0, 0x10AC0, 0x10AFF, 202, 0x10B00, 0x10B3F, 203,
        0x10B40, 0x10B5F, 204, 0x10B60, 0x10B7F, 205, 0x10B80, 0x10BAF, 206,
        0x10BB0, 0x10BFF, 0, 0x10C00, 0x10C4F, 207, 0x10C50, 0x10C7F, 0,
        0x10C80, 0x10CFF, 208, 0x10D00, 0x10D3F, 209, 0x10D40, 0x10D8F, 210,
        0x10D90, 0x10E5F, 0, 0x10E60, 0x10E7F, 211, 0x10E80, 0x10EBF, 212,
        0x10EC0, 0x10EFF, 213, 0x10F00, 0x10F2F, 214, 0x10F30, 0x10F6F, 215,
        0x10F70, 0x10FAF, 216, 0x10FB0, 0x10FDF, 217, 0x10FE0, 0x10FFF, 218,
        0x11000, 0x1107F, 219, 0x11080, 0x110CF, 220, 0x110D0, 0x110FF, 221,
        0x11100, 0x1114F, 222, 0x11150, 0x1117F, 223, 0x11180, 0x111DF, 224,
        0x111E0, 0x111FF, 225, 0x11200, 0x1124F, 226, 0x11250, 0x1127F, 0,
        0x11280, 0x112AF, 227, 0x112B0, 0x112FF, 228, 0x11300, 0x1137F, 229,
        0x11380, 0x113FF, 230, 0x11400, 0x1147F, 231, 0x11480, 0x114DF, 232,
        0x114E0, 0x1157F, 0, 0x11580, 0x115FF, 233, 0x11600, 0x1165F, 234,
        0x11660, 0x1167F, 235, 0x11680, 0x116CF, 236, 0x116D0, 0x116FF, 237,
        0x11700, 0x1174F, 238, 0x11750, 0x117FF, 0, 0x11800, 0x1184F, 239,
        0x11850, 0x1189F, 0, 0x118A0, 0x118FF, 240, 0x11900, 0x1195F, 241,
        0x11960, 0x1199F, 0, 0x119A0, 0x119FF, 242, 0x11A00, 0x11A4F, 243,
        0x11A50, 0x11AAF, 244, 0x11AB0, 0x11ABF, 245, 0x11AC0, 0x11AFF, 246,
        0x11B00, 0x11B5F, 247, 0x11B60, 0x11B7F, 248, 0x11B80, 0x11BBF, 0,
        0x11BC0, 0x11BFF, 249, 0x11C00, 0x11C6F, 250, 0x11C70, 0x11CBF, 251,
        0x11CC0, 0x11CFF, 0, 0x11D00, 0x11D5F, 252, 0x11D60, 0x11DAF, 253,
        0x11DB0, 0x11DEF, 254, 0x11DF0, 0x11EDF, 0, 0x11EE0, 0x11EFF, 255,
        0x11F00, 0x11F5F, 256, 0x11F60, 0x11FAF, 0, 0x11FB0, 0x11FBF, 257,
        0x11FC0, 0x11FFF, 258, 0x12000, 0x123FF, 259, 0x12400, 0x1247F, 260,
        0x12480, 0x1254F, 261, 0x12550, 0x12F8F, 0, 0x12F90, 0x12FFF, 262,
        0x13000, 0x1342F, 263, 0x13430, 0x1345F, 264, 0x13460, 0x143FF, 265,
        0x14400, 0x1467F, 266, 0x14680, 0x160FF, 0, 0x16100, 0x1613F, 267,
        0x16140, 0x167FF, 0, 0x16800, 0x16A3F, 268, 0x16A40, 0x16A6F, 269,
        0x16A70, 0x16ACF, 270, 0x16AD0, 0x16AFF, 271, 0x16B00, 0x16B8F, 272,
        0x16B90, 0x16D3F, 0, 0x16D40, 0x16D7F, 273, 0x16D80, 0x16E3F, 0,
        0x16E40, 0x16E9F, 274, 0x16EA0, 0x16EDF, 275, 0x16EE0, 0x16EFF, 0,
        0x16F00, 0x16F9F, 276, 0x16FA0, 0x16FDF, 0, 0x16FE0, 0x16FFF, 277,
        0x17000, 0x187FF, 278, 0x18800, 0x18AFF, 279, 0x18B00, 0x18CFF, 280,
        0x18D00, 0x18D7F, 281, 0x18D80, 0x18DFF, 282, 0x18E00, 0x1AFEF, 0,
        0x1AFF0, 0x1AFFF, 283, 0x1B000, 0x1B0FF, 284, 0x1B100, 0x1B12F, 285,
        0x1B130, 0x1B16F, 286, 0x1B170, 0x1B2FF, 287, 0x1B300, 0x1BBFF, 0,
        0x1BC00, 0x1BC9F, 288, 0x1BCA0, 0x1BCAF, 289, 0x1BCB0, 0x1CBFF, 0,
        0x1CC00, 0x1CEBF, 290, 0x1CEC0, 0x1CEFF, 291, 0x1CF00, 0x1CFCF, 292,
        0x1CFD0, 0x1CFFF, 0, 0x1D000, 0x1D0FF, 293, 0x1D100, 0x1D1FF, 294,
        0x1D200, 0x1D24F, 295, 0x1D250, 0x1D2BF, 0, 0x1D2C0, 0x1D2DF, 296,
        0x1D2E0, 0x1D2FF, 297, 0x1D300, 0x1D35F, 298, 0x1D360, 0x1D37F, 299,
        0x1D380, 0x1D3FF, 0, 0x1D400, 0x1D7FF, 300, 0x1D800, 0x1DAAF, 301,
        0x1DAB0, 0x1DEFF, 0, 0x1DF00, 0x1DFFF, 302, 0x1E000, 0x1E02F, 303,
        0x1E030, 0x1E08F, 304, 0x1E090, 0x1E0FF, 0, 0x1E100, 0x1E14F, 305,
        0x1E150, 0x1E28F, 0, 0x1E290, 0x1E2BF, 306, 0x1E2C0, 0x1E2FF, 307,
        0x1E300, 0x1E4CF, 0, 0x1E4D0, 0x1E4FF, 308, 0x1E500, 0x1E5CF, 0,
        0x1E5D0, 0x1E5FF, 309, 0x1E600, 0x1E6BF, 0, 0x1E6C0, 0x1E6FF, 310,
        0x1E700, 0x1E7DF, 0, 0x1E7E0, 0x1E7FF, 311, 0x1E800, 0x1E8DF, 312,
        0x1E8E0, 0x1E8FF, 0, 0x1E900, 0x1E95F, 313, 0x1E960, 0x1EC6F, 0,
        0x1EC70, 0x1ECBF, 314, 0x1ECC0, 0x1ECFF, 0, 0x1ED00, 0x1ED4F, 315,
        0x1ED50, 0x1EDFF, 0, 0x1EE00, 0x1EEFF, 316, 0x1EF00, 0x1EFFF, 0,
        0x1F000, 0x1F02F, 317, 0x1F030, 0x1F09F, 318, 0x1F0A0, 0x1F0FF, 319,
        0x1F100, 0x1F1FF, 320, 0x1F200, 0x1F2FF, 321, 0x1F300, 0x1F5FF, 322,
        0x1F600, 0x1F64F, 323, 0x1F650, 0x1F67F, 324, 0x1F680, 0x1F6FF, 325,
        0x1F700, 0x1F77F, 326, 0x1F780, 0x1F7FF, 327, 0x1F800, 0x1F8FF, 328,
        0x1F900, 0x1F9FF, 329, 0x1FA00, 0x1FA6F, 330, 0x1FA70, 0x1FAFF, 331,
        0x1FB00, 0x1FBFF, 332, 0x1FC00, 0x1FFFF, 0, 0x20000, 0x2A6DF, 333,
        0x2A6E0, 0x2A6FF, 0, 0x2A700, 0x2B73F, 334, 0x2B740, 0x2B81F, 335,
        0x2B820, 0x2CEAF, 336, 0x2CEB0, 0x2EBEF, 337, 0x2EBF0, 0x2EE5F, 338,
        0x2EE60, 0x2F7FF, 0, 0x2F800, 0x2FA1F, 339, 0x2FA20, 0x2FFFF, 0,
        0x30000, 0x3134F, 340, 0x31350, 0x323AF, 341, 0x323B0, 0x3347F, 342,
        0x33480, 0xDFFFF, 0, 0xE0000, 0xE007F, 343, 0xE0080, 0xE00FF, 0,
        0xE0100, 0xE01EF, 344, 0xE01F0, 0xEFFFF, 0, 0xF0000, 0xFFFFF, 345,
        0x100000, 0x10FFFF, 346,
    };

    private static final String[] ALIAS_KEYS = {
        "adlam", "aegeannumbers", "ahom", "alchemical", "alchemicalsymbols", "alphabeticpf",
        "alphabeticpresentationforms", "anatolianhieroglyphs", "ancientgreekmusic", "ancientgreekmusicalnotation", "ancientgreeknumbers", "ancientsymbols",
        "arabic", "arabicexta", "arabicextb", "arabicextc", "arabicextendeda", "arabicextendedb",
        "arabicextendedc", "arabicmath", "arabicmathematicalalphabeticsymbols", "arabicpfa", "arabicpfb", "arabicpresentationformsa",
        "arabicpresentationformsb", "arabicsup", "arabicsupplement", "armenian", "arrows", "ascii",
        "avestan", "balinese", "bamum", "bamumsup", "bamumsupplement", "basiclatin",
        "bassavah", "batak", "bengali", "beriaerfe", "bhaiksuki", "blockelements",
        "bopomofo", "bopomofoext", "bopomofoextended", "boxdrawing", "brahmi", "braille",
        "braillepatterns", "buginese", "buhid", "byzantinemusic", "byzantinemusicalsymbols", "canadiansyllabics",
        "carian", "caucasianalbanian", "chakma", "cham", "cherokee", "cherokeesup",
        "cherokeesupplement", "chesssymbols", "chorasmian", "cjk", "cjkcompat", "cjkcompatforms",
        "cjkcompatibility", "cjkcompatibilityforms", "cjkcompatibilityideographs", "cjkcompatibilityideographssupplement", "cjkcompatideographs", "cjkcompatideographssup",
        "cjkexta", "cjkextb", "cjkextc", "cjkextd", "cjkexte", "cjkextf",
        "cjkextg", "cjkexth", "cjkexti", "cjkextj", "cjkradicalssup", "cjkradicalssupplement",
        "cjkstrokes", "cjksymbols", "cjksymbolsandpunctuation", "cjkunifiedideographs", "cjkunifiedideographsextensiona", "cjkunifiedideographsextensionb",
        "cjkunifiedideographsextensionc", "cjkunifiedideographsextensiond", "cjkunifiedideographsextensione", "cjkunifiedideographsextensionf", "cjkunifiedideographsextensiong", "cjkunifiedideographsextensionh",
        "cjkunifiedideographsextensioni", "cjkunifiedideographsextensionj", "combiningdiacriticalmarks", "combiningdiacriticalmarksextended", "combiningdiacriticalmarksforsymbols", "combiningdiacriticalmarkssupplement",
        "combininghalfmarks", "combiningmarksforsymbols", "commonindicnumberforms", "compatjamo", "controlpictures", "coptic",
        "copticepactnumbers", "countingrod", "countingrodnumerals", "cuneiform", "cuneiformnumbers", "cuneiformnumbersandpunctuation",
        "currencysymbols", "cypriotsyllabary", "cyprominoan", "cyrillic", "cyrillicexta", "cyrillicextb",
        "cyrillicextc", "cyrillicextd", "cyrillicextendeda", "cyrillicextendedb", "cyrillicextendedc", "cyrillicextendedd",
        "cyrillicsup", "cyrillicsupplement", "cyrillicsupplementary", "deseret", "devanagari", "devanagariext",
        "devanagariexta", "devanagariextended", "devanagariextendeda", "diacriticals", "diacriticalsext", "diacriticalsforsymbols",
        "diacriticalssup", "dingbats", "divesakuru", "dogra", "domino", "dominotiles",
        "duployan", "earlydynasticcuneiform", "egyptianhieroglyphformatcontrols", "egyptianhieroglyphs", "egyptianhieroglyphsexta", "egyptianhieroglyphsextendeda",
        "elbasan", "elymaic", "emoticons", "enclosedalphanum", "enclosedalphanumerics", "enclosedalphanumericsupplement",
        "enclosedalphanumsup", "enclosedcjk", "enclosedcjklettersandmonths", "enclosedideographicsup", "enclosedideographicsupplement", "ethiopic",
        "ethiopicext", "ethiopicexta", "ethiopicextb", "ethiopicextended", "ethiopicextendeda", "ethiopicextendedb",
        "ethiopicsup", "ethiopicsupplement", "garay", "generalpunctuation", "geometricshapes", "geometricshapesext",
        "geometricshapesextended", "georgian", "georgianext", "georgianextended", "georgiansup", "georgiansupplement",
        "glagolitic", "glagoliticsup", "glagoliticsupplement", "gothic", "grantha", "greek",
        "greekandcoptic", "greekext", "greekextended", "gujarati", "gunjalagondi", "gurmukhi",
        "gurungkhema", "halfandfullforms", "halfmarks", "halfwidthandfullwidthforms", "hangul", "hangulcompatibilityjamo",
        "hanguljamo", "hanguljamoextendeda", "hanguljamoextendedb", "hangulsyllables", "hanifirohingya", "hanunoo",
        "hatran", "hebrew", "highprivateusesurrogates", "highpusurrogates", "highsurrogates", "hiragana",
        "idc", "ideographicdescriptioncharacters", "ideographicsymbols", "ideographicsymbolsandpunctuation", "imperialaramaic", "indicnumberforms",
        "indicsiyaqnumbers", "inscriptionalpahlavi", "inscriptionalparthian", "ipaext", "ipaextensions", "jamo",
        "jamoexta", "jamoextb", "javanese", "kaithi", "kaktoviknumerals", "kanaexta",
        "kanaextb", "kanaextendeda", "kanaextendedb", "kanasup", "kanasupplement", "kanbun",
        "kangxi", "kangxiradicals", "kannada", "katakana", "katakanaext", "katakanaphoneticextensions",
        "kawi", "kayahli", "kharoshthi", "khitansmallscript", "khmer", "khmersymbols",
        "khojki", "khudawadi", "kiratrai", "lao", "latin1", "latin1sup",
        "latin1supplement", "latinexta", "latinextadditional", "latinextb", "latinextc", "latinextd",
        "latinexte", "latinextendeda", "latinextendedadditional", "latinextendedb", "latinextendedc", "latinextendedd",
        "latinextendede", "latinextendedf", "latinextendedg", "latinextf", "latinextg", "lepcha",
        "letterlikesymbols", "limbu", "lineara", "linearbideograms", "linearbsyllabary", "lisu",
        "lisusup", "lisusupplement", "lowsurrogates", "lycian", "lydian", "mahajani",
        "mahjong", "mahjongtiles", "makasar", "malayalam", "mandaic", "manichaean",
        "marchen", "masaramgondi", "mathalphanum", "mathematicalalphanumericsymbols", "mathematicaloperators", "mathoperators",
        "mayannumerals", "medefaidrin", "meeteimayek", "meeteimayekext", "meeteimayekextensions", "mendekikakui",
        "meroiticcursive", "meroitichieroglyphs", "miao", "miscarrows", "miscellaneousmathematicalsymbolsa", "miscellaneousmathematicalsymbolsb",
        "miscellaneoussymbols", "miscellaneoussymbolsandarrows", "miscellaneoussymbolsandpictographs", "miscellaneoussymbolssupplement", "miscellaneoustechnical", "miscmathsymbolsa",
        "miscmathsymbolsb", "miscpictographs", "miscsymbols", "miscsymbolssup", "misctechnical", "modi",
        "modifierletters", "modifiertoneletters", "mongolian", "mongoliansup", "mongoliansupplement", "mro",
        "multani", "music", "musicalsymbols", "myanmar", "myanmarexta", "myanmarextb",
        "myanmarextc", "myanmarextendeda", "myanmarextendedb", "myanmarextendedc", "nabataean", "nagmundari",
        "nandinagari", "nb", "newa", "newtailue", "nko", "noblock",
        "numberforms", "nushu", "nyiakengpuachuehmong", "ocr", "ogham", "olchiki",
        "oldhungarian", "olditalic", "oldnortharabian", "oldpermic", "oldpersian", "oldsogdian",
        "oldsoutharabian", "oldturkic", "olduyghur", "olonal", "opticalcharacterrecognition", "oriya",
        "ornamentaldingbats", "osage", "osmanya", "ottomansiyaqnumbers", "pahawhhmong", "palmyrene",
        "paucinhau", "phagspa", "phaistos", "phaistosdisc", "phoenician", "phoneticext",
        "phoneticextensions", "phoneticextensionssupplement", "phoneticextsup", "playingcards", "privateuse", "privateusearea",
        "psalterpahlavi", "pua", "punctuation", "rejang", "rumi", "ruminumeralsymbols",
        "runic", "samaritan", "saurashtra", "sharada", "sharadasup", "sharadasupplement",
        "shavian", "shorthandformatcontrols", "siddham", "sidetic", "sinhala", "sinhalaarchaicnumbers",
        "smallforms", "smallformvariants", "smallkanaext", "smallkanaextension", "sogdian", "sorasompeng",
        "soyombo", "spacingmodifierletters", "specials", "sundanese", "sundanesesup", "sundanesesupplement",
        "sunuwar", "suparrowsa", "suparrowsb", "suparrowsc", "superandsub", "superscriptsandsubscripts",
        "supmathoperators", "supplementalarrowsa", "supplementalarrowsb", "supplementalarrowsc", "supplementalmathematicaloperators", "supplementalpunctuation",
        "supplementalsymbolsandpictographs", "supplementaryprivateuseareaa", "supplementaryprivateuseareab", "suppuaa", "suppuab", "suppunctuation",
        "supsymbolsandpictographs", "suttonsignwriting", "sylotinagri", "symbolsandpictographsexta", "symbolsandpictographsextendeda", "symbolsforlegacycomputing",
        "symbolsforlegacycomputingsup", "symbolsforlegacycomputingsupplement", "syriac", "syriacsup", "syriacsupplement", "tagalog",
        "tagbanwa", "tags", "taile", "taitham", "taiviet", "taixuanjing",
        "taixuanjingsymbols", "taiyo", "takri", "tamil", "tamilsup", "tamilsupplement",
        "tangsa", "tangut", "tangutcomponents", "tangutcomponentssup", "tangutcomponentssupplement", "tangutsup",
        "tangutsupplement", "telugu", "thaana", "thai", "tibetan", "tifinagh",
        "tirhuta", "todhri", "tolongsiki", "toto", "transportandmap", "transportandmapsymbols",
        "tulutigalari", "ucas", "ucasext", "ucasexta", "ugaritic", "unifiedcanadianaboriginalsyllabics",
        "unifiedcanadianaboriginalsyllabicsextended", "unifiedcanadianaboriginalsyllabicsextendeda", "vai", "variationselectors", "variationselectorssupplement", "vedicext",
        "vedicextensions", "verticalforms", "vithkuqi", "vs", "vssup", "wancho",
        "warangciti", "yezidi", "yijing", "yijinghexagramsymbols", "yiradicals", "yisyllables",
        "zanabazarsquare", "znamennymusic", "znamennymusicalnotation",
    };

    private static final short[] ALIAS_VALUE_IDS = {
        313, 167, 238, 326, 326, 155, 155, 266, 295, 295, 168, 169, 13, 22, 21, 213, 22, 21, 213, 316,
        316, 156, 162, 156, 162, 15, 15, 11, 79, 1, 203, 59, 127, 268, 268, 1, 271, 61, 24, 275,
        250, 86, 111, 114, 114, 85, 219, 92, 92, 56, 47, 293, 293, 42, 172, 184, 222, 140, 41, 146,
        146, 330, 217, 121, 118, 160, 118, 160, 154, 339, 154, 339, 119, 333, 334, 335, 336, 337, 340, 341,
        338, 342, 105, 105, 115, 108, 108, 121, 119, 333, 334, 335, 336, 337, 340, 341, 338, 342, 7, 58,
        76, 70, 159, 76, 131, 112, 82, 99, 173, 299, 299, 259, 260, 260, 75, 189, 262, 9, 103, 126,
        64, 304, 103, 126, 64, 304, 10, 10, 10, 179, 23, 134, 247, 134, 247, 7, 58, 76, 70, 89,
        241, 239, 318, 318, 288, 261, 264, 263, 265, 265, 183, 218, 323, 84, 84, 320, 320, 117, 117, 321,
        321, 39, 102, 144, 311, 102, 144, 311, 40, 40, 210, 73, 87, 327, 327, 37, 65, 65, 100, 100,
        97, 303, 303, 175, 229, 8, 8, 72, 72, 26, 253, 25, 267, 163, 159, 163, 148, 112, 38, 137,
        149, 148, 209, 46, 193, 12, 151, 151, 150, 109, 107, 107, 277, 277, 190, 131, 314, 205, 204, 5,
        5, 38, 137, 149, 138, 220, 296, 285, 283, 285, 283, 284, 284, 113, 106, 106, 30, 110, 116, 116,
        256, 135, 199, 280, 49, 55, 226, 228, 273, 34, 2, 2, 2, 3, 71, 4, 98, 129, 145, 3,
        71, 4, 98, 129, 145, 188, 302, 188, 302, 62, 77, 52, 187, 166, 165, 124, 257, 257, 152, 171,
        195, 223, 317, 317, 255, 31, 19, 202, 251, 252, 300, 300, 80, 80, 297, 274, 147, 143, 143, 312,
        198, 197, 276, 96, 90, 94, 88, 96, 322, 291, 81, 90, 94, 322, 88, 291, 81, 234, 6, 128,
        50, 235, 235, 269, 227, 294, 294, 36, 141, 139, 237, 141, 139, 237, 192, 308, 242, 0, 231, 54,
        17, 0, 78, 287, 305, 83, 43, 63, 208, 174, 201, 176, 178, 214, 200, 207, 216, 309, 83, 27,
        324, 182, 181, 315, 272, 191, 246, 132, 170, 170, 194, 68, 68, 69, 69, 319, 153, 153, 206, 153,
        73, 136, 211, 211, 44, 18, 133, 224, 248, 248, 180, 289, 233, 196, 32, 225, 161, 161, 286, 286,
        215, 221, 244, 6, 164, 60, 66, 66, 249, 91, 93, 328, 74, 74, 95, 91, 93, 328, 95, 104,
        329, 345, 346, 345, 346, 104, 329, 301, 130, 331, 331, 332, 290, 290, 14, 20, 20, 45, 48, 343,
        53, 57, 142, 298, 298, 310, 236, 28, 258, 258, 270, 278, 279, 282, 282, 281, 281, 29, 16, 33,
        35, 101, 232, 186, 254, 306, 325, 325, 230, 42, 51, 245, 177, 42, 51, 245, 125, 157, 344, 67,
        67, 158, 185, 157, 344, 307, 240, 212, 120, 120, 123, 122, 243, 292, 292,
    };

    private static final UnicodeSet[] SETS = buildSets();

    static int valueCount() {
        return VALUE_NAMES.length;
    }

    static int aliasCount() {
        return ALIAS_KEYS.length;
    }

    static int rangeCount() {
        return RANGES.length / 3;
    }

    static String canonicalValue(int valueId) {
        return VALUE_NAMES[valueId];
    }

    static UnicodeSet set(int valueId) {
        return SETS[valueId];
    }

    static UnicodeSet set(String valueAlias) {
        short valueId = value(valueAlias);
        return valueId == INVALID ? null : SETS[valueId];
    }

    static short value(String valueAlias) {
        String key = loose(valueAlias);
        int index = Arrays.binarySearch(ALIAS_KEYS, key);
        return index < 0 ? INVALID : ALIAS_VALUE_IDS[index];
    }

    static boolean isPropertyAlias(String alias) {
        boolean hasIsPrefix = alias != null && alias.startsWith("Is");
        String normalized = loose(hasIsPrefix ? alias.substring(2) : alias);
        return normalized.equals("blk") || normalized.equals("block");
    }

    private static UnicodeSet[] buildSets() {
        UnicodeSet[] sets = new UnicodeSet[VALUE_NAMES.length];
        for (int valueId = 0; valueId < sets.length; valueId++) {
            sets[valueId] = new UnicodeSet();
        }
        for (int offset = 0; offset < RANGES.length; offset += 3) {
            sets[RANGES[offset + 2]].add(RANGES[offset], RANGES[offset + 1]);
        }
        for (int valueId = 0; valueId < sets.length; valueId++) {
            sets[valueId].freeze();
        }
        return sets;
    }

    private static String loose(String alias) {
        if (alias == null) return "";
        StringBuilder normalized = new StringBuilder(alias.length());
        for (int i = 0; i < alias.length(); i++) {
            char character = alias.charAt(i);
            if (character == '_' || character == '-' || character == ' '
                    || (character >= '\t' && character <= '\r')) continue;
            normalized.append(Character.toLowerCase(character));
        }
        return normalized.toString();
    }

    private PerlUnicodeBlockData() {
    }
}
