/*
 * Generated from hash-verified Unicode Character Database sources in the
 * selected current Perl 5.45.3 checkout. Do not edit manually.
 *
 * Source: Unikemet-17.0.0.txt
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the
 * U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;

final class PerlUnicodeUnikemetData {
    static final String UNICODE_VERSION = "17.0.0";
    static final String SOURCE_DATE = "2025-07-21";
    static final String UNIKEMET_SHA256 = "76a3081265e6eb673873f9c93d6f36062e82c7ed027c5c1a592accfbe48c20a5";
    static final int NO_MIRROR_CODE_POINT_COUNT = 4;
    static final int NO_ROTATE_CODE_POINT_COUNT = 44;

    private static final int[] NO_MIRROR_RANGES = {
        0x13081, 0x13081, 0x13084, 0x13084, 0x130BB, 0x130BB, 0x130BD, 0x130BD,
    };

    private static final int[] NO_ROTATE_RANGES = {
        0x13021, 0x13021, 0x1303F, 0x1303F, 0x130AD, 0x130AD, 0x130B7, 0x130B7,
        0x13131, 0x13131, 0x131B1, 0x131B1, 0x131EF, 0x131EF, 0x1327D, 0x1327D,
        0x13285, 0x13285, 0x1328A, 0x1328A, 0x1329B, 0x1329C, 0x13308, 0x13309,
        0x13355, 0x13355, 0x13361, 0x13361, 0x13371, 0x13372, 0x13403, 0x13403,
        0x1342A, 0x1342B, 0x13489, 0x13489, 0x134BC, 0x134BC, 0x134BF, 0x134BF,
        0x13948, 0x13948, 0x1394A, 0x1394A, 0x13A12, 0x13A13, 0x13A16, 0x13A16,
        0x13BD5, 0x13BD5, 0x13E0F, 0x13E0F, 0x13EC2, 0x13EC2, 0x13F72, 0x13F73,
        0x1401F, 0x1401F, 0x140A4, 0x140A4, 0x14190, 0x14190, 0x1425E, 0x1425E,
        0x1431C, 0x1431C, 0x14388, 0x14388, 0x14399, 0x1439B, 0x143E8, 0x143E8,
    };

    private static final UnicodeSet NO_MIRROR = buildSet(NO_MIRROR_RANGES);
    private static final UnicodeSet NO_ROTATE = buildSet(NO_ROTATE_RANGES);

    static UnicodeSet noMirror() {
        return NO_MIRROR;
    }

    static UnicodeSet noRotate() {
        return NO_ROTATE;
    }

    static int noMirrorRangeCount() {
        return NO_MIRROR_RANGES.length / 2;
    }

    static int noRotateRangeCount() {
        return NO_ROTATE_RANGES.length / 2;
    }

    private static UnicodeSet buildSet(int[] ranges) {
        UnicodeSet set = new UnicodeSet();
        for (int index = 0; index < ranges.length; index += 2) {
            set.add(ranges[index], ranges[index + 1]);
        }
        return set.freeze();
    }

    private PerlUnicodeUnikemetData() {
    }
}
