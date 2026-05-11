package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

/**
 * Java XS replacement for {@code Unicode::Collate} (see {@code perl5/cpan/Unicode-Collate/Collate.xs}).
 * <p>
 * The bundled {@code Unicode/Collate.pm} is patched to always use file-based DUCET tables
 * ({@code allkeys.txt} / {@code keys.txt}) instead of the XS-packed {@code ucatbl.h} trie.
 * This class implements the remaining XSUBs: hex parsing, Hangul decomposition, derived CEs,
 * sort-key assembly, variable CE handling, and sort-key visualization.
 */
public class UnicodeCollate extends PerlModuleBase {

    public static final String XS_VERSION = "1.31";

    private static final int VCE_LENGTH = 9;
    private static final int MAX_LEVEL = 4;
    private static final int SHIFT_4_WT = 0xFFFF;
    private static final BigInteger UV_MAX_BI = new BigInteger("FFFFFFFFFFFFFFFF", 16);
    private static final BigInteger MAX_DIV_16 = UV_MAX_BI.divide(BigInteger.valueOf(16));

    private static final int Hangul_SBase = 0xAC00;
    private static final int Hangul_SIni = 0xAC00;
    private static final int Hangul_SFin = 0xD7A3;
    private static final int Hangul_NCount = 588;
    private static final int Hangul_TCount = 28;
    private static final int Hangul_LBase = 0x1100;
    private static final int Hangul_LIni = 0x1100;
    private static final int Hangul_LFin = 0x1159;
    private static final int Hangul_LFill = 0x115F;
    private static final int Hangul_LEnd = 0x115F;
    private static final int Hangul_VBase = 0x1161;
    private static final int Hangul_VIni = 0x1160;
    private static final int Hangul_VFin = 0x11A2;
    private static final int Hangul_VEnd = 0x11A7;
    private static final int Hangul_TBase = 0x11A7;
    private static final int Hangul_TIni = 0x11A8;
    private static final int Hangul_TFin = 0x11F9;
    private static final int Hangul_TEnd = 0x11FF;
    private static final int HangulL2Ini = 0xA960;
    private static final int HangulL2Fin = 0xA97C;
    private static final int HangulV2Ini = 0xD7B0;
    private static final int HangulV2Fin = 0xD7C6;
    private static final int HangulT2Ini = 0xD7CB;
    private static final int HangulT2Fin = 0xD7FB;

    private static final int CJK_UidIni = 0x4E00;
    private static final int CJK_UidFin = 0x9FA5;
    private static final int CJK_UidF41 = 0x9FBB;
    private static final int CJK_UidF51 = 0x9FC3;
    private static final int CJK_UidF52 = 0x9FCB;
    private static final int CJK_UidF61 = 0x9FCC;
    private static final int CJK_UidF80 = 0x9FD5;
    private static final int CJK_UidF100 = 0x9FEA;
    private static final int CJK_UidF110 = 0x9FEF;
    private static final int CJK_UidF130 = 0x9FFC;

    private static final int CJK_ExtAIni = 0x3400;
    private static final int CJK_ExtAFin = 0x4DB5;
    private static final int CJK_ExtA130 = 0x4DBF;
    private static final int CJK_ExtBIni = 0x20000;
    private static final int CJK_ExtBFin = 0x2A6D6;
    private static final int CJK_ExtB130 = 0x2A6DD;
    private static final int CJK_ExtCIni = 0x2A700;
    private static final int CJK_ExtCFin = 0x2B734;
    private static final int CJK_ExtDIni = 0x2B740;
    private static final int CJK_ExtDFin = 0x2B81D;
    private static final int CJK_ExtEIni = 0x2B820;
    private static final int CJK_ExtEFin = 0x2CEA1;
    private static final int CJK_ExtFIni = 0x2CEB0;
    private static final int CJK_ExtFFin = 0x2EBE0;
    private static final int CJK_ExtGIni = 0x30000;
    private static final int CJK_ExtGFin = 0x3134A;

    private static final int CJK_CompIni = 0xFA0E;
    private static final int CJK_CompFin = 0xFA29;
    private static final byte[] UNIFIED_COMPAT = {
            1, 1, 0, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0, 1, 1, 0, 0, 1, 1, 1
    };

    private static final int TangIdeoIni = 0x17000;
    private static final int TangIdeoFin = 0x187EC;
    private static final int TangIdeo110 = 0x187F1;
    private static final int TangIdeo120 = 0x187F7;
    private static final int TangCompIni = 0x18800;
    private static final int TangCompFin = 0x18AF2;
    private static final int TangComp130 = 0x18AFF;
    private static final int TangSuppIni = 0x18D00;
    private static final int TangSuppFin = 0x18D08;
    private static final int NushuIni = 0x1B170;
    private static final int NushuFin = 0x1B2FB;
    private static final int KhitanIni = 0x18B00;
    private static final int KhitanFin = 0x18CD5;

    public UnicodeCollate() {
        super("Unicode::Collate", false);
    }

    public static void initialize() {
        UnicodeCollate m = new UnicodeCollate();
        try {
            m.registerMethod("_getHexArray", null);
            m.registerMethod("_isIllegal", null);
            m.registerMethod("_decompHangul", null);
            m.registerMethod("getHST", null);
            m.registerMethod("_derivCE_8", null);
            m.registerMethod("_derivCE_9", null);
            m.registerMethod("_derivCE_14", null);
            m.registerMethod("_derivCE_18", null);
            m.registerMethod("_derivCE_20", null);
            m.registerMethod("_derivCE_22", null);
            m.registerMethod("_derivCE_24", null);
            m.registerMethod("_derivCE_32", null);
            m.registerMethod("_derivCE_34", null);
            m.registerMethod("_derivCE_36", null);
            m.registerMethod("_derivCE_38", null);
            m.registerMethod("_derivCE_40", null);
            m.registerMethod("_derivCE_43", null);
            m.registerMethod("_uideoCE_8", null);
            m.registerMethod("_isUIdeo", null);
            m.registerMethod("mk_SortKey", null);
            m.registerMethod("varCE", null);
            m.registerMethod("visualizeSortKey", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Unicode::Collate method: " + e.getMessage());
        }
    }

    private static RuntimeHash expectHashRef(RuntimeScalar ref, String ctx) {
        if (ref == null || !ref.getBoolean()) {
            WarnDie.die(new RuntimeScalar("$self is not a HASHREF. at " + ctx + ".\n"), new RuntimeScalar("\n"));
            throw new AssertionError();
        }
        return ref.hashDeref();
    }

    private static RuntimeArray expectArrayRef(RuntimeScalar ref, String ctx) {
        if (ref == null || !ref.getBoolean()) {
            WarnDie.die(new RuntimeScalar("XSUB, not an ARRAYREF. at " + ctx + ".\n"), new RuntimeScalar("\n"));
            throw new AssertionError();
        }
        return ref.arrayDeref();
    }

    /** Plain hash lookup — avoids {@link RuntimeHash#get} autovivification proxy on missing keys. */
    private static RuntimeScalar hashGet(RuntimeHash h, String key) {
        return h.elements.get(key);
    }

    /** XS: _getHexArray */
    public static RuntimeList _getHexArray(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeList();
        }
        String s = args.get(0).toString();
        RuntimeList out = new RuntimeList();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i++);
            int digit = hexVal(c);
            if (digit < 0) {
                continue;
            }
            BigInteger value = BigInteger.valueOf(digit);
            boolean overflowed = false;
            while (i < n) {
                char c2 = s.charAt(i);
                int d2 = hexVal(c2);
                if (d2 < 0) {
                    break;
                }
                i++;
                if (overflowed) {
                    continue;
                }
                if (value.compareTo(MAX_DIV_16) > 0) {
                    overflowed = true;
                    continue;
                }
                value = value.shiftLeft(4).or(BigInteger.valueOf(d2));
            }
            if (overflowed) {
                out.add(new RuntimeScalar(UV_MAX_BI.toString()));
            } else {
                out.add(new RuntimeScalar(value.longValue()));
            }
        }
        return out;
    }

    /**
     * Perl {@code SvIOK} can be true for a string of decimal digits.  {@code unpack('U*')}
     * sometimes returns such a STRING (not {@code DUALVAR}) while still numifying to the UV.
     */
    private static boolean hasPerlNumericUv(RuntimeScalar sv) {
        return switch (sv.type) {
            case RuntimeScalarType.INTEGER,
                    RuntimeScalarType.DOUBLE,
                    RuntimeScalarType.DUALVAR,
                    RuntimeScalarType.BOOLEAN -> true;
            case RuntimeScalarType.STRING, RuntimeScalarType.BYTE_STRING -> {
                String s = sv.toString().trim();
                yield !s.isEmpty() && s.chars().allMatch(Character::isDigit);
            }
            default -> false;
        };
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** XS: _isIllegal */
    public static RuntimeList _isIllegal(RuntimeArray args, int ctx) {
        // XS: !sv → YES (illegal).  Do not use getBoolean(): UV 0 (NUL) is falsy
        // but is a legal code point for collation tables.
        if (args.isEmpty() || !args.get(0).getDefinedBoolean()) {
            return scalarTrue.getList();
        }
        RuntimeScalar sv = args.get(0);
        if (!hasPerlNumericUv(sv)) {
            return scalarTrue.getList();
        }
        long uv = sv.getUnsignedLong().longValue();
        boolean bad = (uv > 0x10FFFFL)
                || ((uv & 0xFFFE) == 0xFFFE)
                || (uv >= 0xD800 && uv <= 0xDFFF)
                || (uv >= 0xFDD0 && uv <= 0xFDEF);
        return (bad ? scalarTrue : scalarFalse).getList();
    }

    /** XS: _decompHangul */
    public static RuntimeList _decompHangul(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeList();
        }
        long code = args.get(0).getUnsignedLong().longValue();
        long sindex = code - Hangul_SBase;
        long lindex = sindex / Hangul_NCount;
        long vindex = (sindex % Hangul_NCount) / Hangul_TCount;
        long tindex = sindex % Hangul_TCount;
        RuntimeList list = new RuntimeList();
        list.add(new RuntimeScalar((int) (lindex + Hangul_LBase)));
        list.add(new RuntimeScalar((int) (vindex + Hangul_VBase)));
        if (tindex != 0) {
            list.add(new RuntimeScalar((int) (tindex + Hangul_TBase)));
        }
        return list;
    }

    private static boolean codeRange(long code, int b, int e) {
        return b <= code && code <= e;
    }

    /** XS: getHST */
    public static RuntimeList getHST(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeList(new RuntimeScalar(""));
        }
        long code = args.get(0).getUnsignedLong().longValue();
        int ucaVers = args.size() > 1 ? args.get(1).getInt() : 0;
        String hangtype;
        if (codeRange(code, Hangul_SIni, Hangul_SFin)) {
            if ((code - Hangul_SBase) % Hangul_TCount != 0) {
                hangtype = "LVT";
            } else {
                hangtype = "LV";
            }
        } else if (ucaVers < 20) {
            if (codeRange(code, Hangul_LIni, Hangul_LFin) || code == Hangul_LFill) {
                hangtype = "L";
            } else if (codeRange(code, Hangul_VIni, Hangul_VFin)) {
                hangtype = "V";
            } else if (codeRange(code, Hangul_TIni, Hangul_TFin)) {
                hangtype = "T";
            } else {
                hangtype = "";
            }
        } else {
            if (codeRange(code, Hangul_LIni, Hangul_LEnd) || codeRange(code, HangulL2Ini, HangulL2Fin)) {
                hangtype = "L";
            } else if (codeRange(code, Hangul_VIni, Hangul_VEnd) || codeRange(code, HangulV2Ini, HangulV2Fin)) {
                hangtype = "V";
            } else if (codeRange(code, Hangul_TIni, Hangul_TEnd) || codeRange(code, HangulT2Ini, HangulT2Fin)) {
                hangtype = "T";
            } else {
                hangtype = "";
            }
        }
        return new RuntimeList(new RuntimeScalar(hangtype));
    }

    /** XS: _derivCE_8 */
    public static RuntimeList _derivCE_8(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeList();
        }
        int code = args.get(0).getInt();
        return pushDerivPair(code, 0xFF80 + (code >> 15), (code & 0x7FFF) | 0x8000, 0x02, 0x01);
    }

    private static RuntimeList pushDerivPair(int code, int aaaa, int bbbb, int l2Second, int l3Second) {
        byte[] a = new byte[VCE_LENGTH];
        byte[] b = new byte[VCE_LENGTH];
        a[1] = (byte) ((aaaa >> 8) & 0xFF);
        a[2] = (byte) (aaaa & 0xFF);
        b[1] = (byte) ((bbbb >> 8) & 0xFF);
        b[2] = (byte) (bbbb & 0xFF);
        a[4] = (byte) l2Second;
        a[6] = (byte) l3Second;
        a[7] = b[7] = (byte) ((code >> 8) & 0xFF);
        a[8] = b[8] = (byte) (code & 0xFF);
        RuntimeList list = new RuntimeList();
        list.add(new RuntimeScalar(a));
        list.add(new RuntimeScalar(b));
        return list;
    }

    private static RuntimeList derivCeUnified(RuntimeArray args, int ix) {
        if (args.isEmpty()) {
            return new RuntimeList();
        }
        int code = args.get(0).getInt();
        boolean basicUnified = false;
        boolean tangut = false;
        boolean nushu = false;
        boolean khitan = false;

        if (codeRange(code, CJK_UidIni, CJK_CompFin)) {
            if (codeRange(code, CJK_CompIni, CJK_CompFin)) {
                basicUnified = UNIFIED_COMPAT[code - CJK_CompIni] != 0;
            } else {
                basicUnified = switch (ix) {
                    case 11 -> code <= CJK_UidF130;
                    case 9, 10 -> code <= CJK_UidF110;
                    case 8 -> code <= CJK_UidF100;
                    case 6, 7 -> code <= CJK_UidF80;
                    case 5 -> code <= CJK_UidF61;
                    case 3, 4 -> code <= CJK_UidF52;
                    case 2 -> code <= CJK_UidF51;
                    case 1 -> code <= CJK_UidF41;
                    default -> code <= CJK_UidFin;
                };
            }
        } else {
            if (ix >= 7) {
                tangut = switch (ix) {
                    case 11 -> codeRange(code, TangIdeoIni, TangIdeo120)
                            || codeRange(code, TangCompIni, TangComp130)
                            || codeRange(code, TangSuppIni, TangSuppFin);
                    case 10 -> codeRange(code, TangIdeoIni, TangIdeo120)
                            || codeRange(code, TangCompIni, TangCompFin);
                    case 9 -> codeRange(code, TangIdeoIni, TangIdeo110)
                            || codeRange(code, TangCompIni, TangCompFin);
                    default -> codeRange(code, TangIdeoIni, TangIdeoFin)
                            || codeRange(code, TangCompIni, TangCompFin);
                };
            }
            if (ix >= 8) {
                nushu = codeRange(code, NushuIni, NushuFin);
            }
            if (ix >= 11) {
                khitan = codeRange(code, KhitanIni, KhitanFin);
            }
        }

        int base = tangut ? 0xFB00 : (nushu ? 0xFB01 : (khitan ? 0xFB02 : (basicUnified ? 0xFB40
                : (((ix >= 11 ? codeRange(code, CJK_ExtAIni, CJK_ExtA130) : codeRange(code, CJK_ExtAIni, CJK_ExtAFin))
                || (ix >= 11 ? codeRange(code, CJK_ExtBIni, CJK_ExtB130) : codeRange(code, CJK_ExtBIni, CJK_ExtBFin))
                || (ix >= 3 && codeRange(code, CJK_ExtCIni, CJK_ExtCFin))
                || (ix >= 4 && codeRange(code, CJK_ExtDIni, CJK_ExtDFin))
                || (ix >= 6 && codeRange(code, CJK_ExtEIni, CJK_ExtEFin))
                || (ix >= 8 && codeRange(code, CJK_ExtFIni, CJK_ExtFFin))
                || (ix >= 11 && codeRange(code, CJK_ExtGIni, CJK_ExtGFin)))
                ? 0xFB80 : 0xFBC0))));

        int aaaa = tangut || nushu || khitan ? base : base + (code >> 15);
        // UCA implicit primary: OR 0x8000 onto the low 15 bits. '|' binds tighter than '?:', so a
        // single combined expression parses as tangut ? (code - ini) : ((… ) | 0x8000) and drops the
        // flag on the Tangut branch; compute the payload first, then OR (matches Collate.pm).
        int derivLow = tangut ? (code - TangIdeoIni) : (nushu ? (code - NushuIni) : (khitan ? (code - KhitanIni) : (code & 0x7FFF)));
        int bbbb = derivLow | 0x8000;

        byte[] a = new byte[VCE_LENGTH];
        byte[] b = new byte[VCE_LENGTH];
        a[1] = (byte) ((aaaa >> 8) & 0xFF);
        a[2] = (byte) (aaaa & 0xFF);
        b[1] = (byte) ((bbbb >> 8) & 0xFF);
        b[2] = (byte) (bbbb & 0xFF);
        a[4] = 0x20;
        a[6] = 0x02;
        a[7] = b[7] = (byte) ((code >> 8) & 0xFF);
        a[8] = b[8] = (byte) (code & 0xFF);
        RuntimeList list = new RuntimeList();
        list.add(new RuntimeScalar(a));
        list.add(new RuntimeScalar(b));
        return list;
    }

    public static RuntimeList _derivCE_9(RuntimeArray args, int ctx) {
        return derivCeUnified(args, 0);
    }

    public static RuntimeList _derivCE_14(RuntimeArray args, int ctx) {
        return derivCeUnified(args, 1);
    }

    public static RuntimeList _derivCE_18(RuntimeArray args, int ctx) {
        return derivCeUnified(args, 2);
    }

    public static RuntimeList _derivCE_20(RuntimeArray args, int ctx) {
        return derivCeUnified(args, 3);
    }

    public static RuntimeList _derivCE_22(RuntimeArray args, int ctx) {
        return derivCeUnified(args, 4);
    }

    public static RuntimeList _derivCE_24(RuntimeArray args, int ctx) {
        return derivCeUnified(args, 5);
    }

    public static RuntimeList _derivCE_32(RuntimeArray args, int ctx) {
        return derivCeUnified(args, 6);
    }

    public static RuntimeList _derivCE_34(RuntimeArray args, int ctx) {
        return derivCeUnified(args, 7);
    }

    public static RuntimeList _derivCE_36(RuntimeArray args, int ctx) {
        return derivCeUnified(args, 8);
    }

    public static RuntimeList _derivCE_38(RuntimeArray args, int ctx) {
        return derivCeUnified(args, 9);
    }

    public static RuntimeList _derivCE_40(RuntimeArray args, int ctx) {
        return derivCeUnified(args, 10);
    }

    public static RuntimeList _derivCE_43(RuntimeArray args, int ctx) {
        return derivCeUnified(args, 11);
    }

    /** XS: _uideoCE_8 */
    public static RuntimeList _uideoCE_8(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeList();
        }
        int code = args.get(0).getInt();
        byte[] uice = new byte[VCE_LENGTH];
        uice[1] = uice[7] = (byte) ((code >> 8) & 0xFF);
        uice[2] = uice[8] = (byte) (code & 0xFF);
        uice[4] = 0x20;
        uice[6] = 0x02;
        return new RuntimeList(new RuntimeScalar(uice));
    }

    /** XS: _isUIdeo */
    public static RuntimeList _isUIdeo(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarFalse.getList();
        }
        long code = args.get(0).getUnsignedLong().longValue();
        int ucaVers = args.size() > 1 ? args.get(1).getInt() : 0;
        boolean basicUnified = false;
        if (code >= CJK_UidIni) {
            if (codeRange(code, CJK_CompIni, CJK_CompFin)) {
                basicUnified = UNIFIED_COMPAT[(int) (code - CJK_CompIni)] != 0;
            } else {
                basicUnified = ucaVers >= 43 ? code <= CJK_UidF130
                        : ucaVers >= 38 ? code <= CJK_UidF110
                        : ucaVers >= 36 ? code <= CJK_UidF100
                        : ucaVers >= 32 ? code <= CJK_UidF80
                        : ucaVers >= 24 ? code <= CJK_UidF61
                        : ucaVers >= 20 ? code <= CJK_UidF52
                        : ucaVers >= 18 ? code <= CJK_UidF51
                        : ucaVers >= 14 ? code <= CJK_UidF41
                        : code <= CJK_UidFin;
            }
        }
        boolean ok = basicUnified
                || codeRange(code, CJK_ExtAIni, CJK_ExtAFin)
                || (ucaVers >= 43 && codeRange(code, CJK_ExtAIni, CJK_ExtA130))
                || (ucaVers >= 8 && codeRange(code, CJK_ExtBIni, CJK_ExtBFin))
                || (ucaVers >= 43 && codeRange(code, CJK_ExtBIni, CJK_ExtB130))
                || (ucaVers >= 20 && codeRange(code, CJK_ExtCIni, CJK_ExtCFin))
                || (ucaVers >= 22 && codeRange(code, CJK_ExtDIni, CJK_ExtDFin))
                || (ucaVers >= 32 && codeRange(code, CJK_ExtEIni, CJK_ExtEFin))
                || (ucaVers >= 36 && codeRange(code, CJK_ExtFIni, CJK_ExtFFin))
                || (ucaVers >= 43 && codeRange(code, CJK_ExtGIni, CJK_ExtGFin));
        return (ok ? scalarTrue : scalarFalse).getList();
    }

    /** XS: mk_SortKey */
    public static RuntimeList mk_SortKey(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeList(new RuntimeScalar(""));
        }
        RuntimeHash selfHV = expectHashRef(args.get(0), "mk_SortKey");
        RuntimeArray bufAV = expectArrayRef(args.get(1), "mk_SortKey");
        int bufLen = bufAV.size() - 1;

        if (bufLen < 0) {
            int dlen = 2 * (MAX_LEVEL - 1);
            byte[] z = new byte[dlen];
            return new RuntimeList(new RuntimeScalar(z));
        }

        RuntimeScalar levelSv = hashGet(selfHV, "level");
        int level = (levelSv != null && levelSv.getDefinedBoolean()) ? levelSv.getInt() : MAX_LEVEL;

        RuntimeScalar ulSv = hashGet(selfHV, "upper_before_lower");
        boolean upperLower = ulSv != null && ulSv.getDefinedBoolean() && ulSv.getBoolean();
        RuntimeScalar khSv = hashGet(selfHV, "katakana_before_hiragana");
        boolean kataHira = khSv != null && khSv.getDefinedBoolean() && khSv.getBoolean();
        RuntimeScalar ucaSv = hashGet(selfHV, "UCA_Version");
        int ucaVers = (ucaSv != null && ucaSv.getDefinedBoolean()) ? ucaSv.getInt() : 43;
        RuntimeScalar varSv = hashGet(selfHV, "variable");
        boolean v2i = ucaVers >= 9 && varSv != null && varSv.getDefinedBoolean()
                && !(varSv.toString().length() == 13 && "non-ignorable".equals(varSv.toString()));

        byte[][] eachLevel = new byte[MAX_LEVEL][];
        int[][] sPtr = new int[MAX_LEVEL][];
        for (int lv = 0; lv < level; lv++) {
            eachLevel[lv] = new byte[2 * (1 + bufLen) + 1];
            sPtr[lv] = new int[]{0};
        }

        boolean lastIsVar = false;
        for (int i = 0; i <= bufLen; i++) {
            RuntimeScalar cell = bufAV.get(i);
            if (cell == null || !cell.getDefinedBoolean()) {
                WarnDie.die(new RuntimeScalar("not a vwt.\n"), new RuntimeScalar("\n"));
            }
            byte[] vOrig = latin1Bytes(cell);
            if (vOrig.length < VCE_LENGTH) {
                continue;
            }
            byte[] v = Arrays.copyOf(vOrig, vOrig.length);

            if (v2i) {
                if (v[0] != 0) {
                    lastIsVar = true;
                } else if ((v[1] & 0xFF) != 0 || (v[2] & 0xFF) != 0) {
                    lastIsVar = false;
                } else if (lastIsVar) {
                    continue;
                }
            }

            if ((v[5] & 0xFF) == 0) {
                if (upperLower) {
                    int t6 = v[6] & 0xFF;
                    if (t6 >= 0x8 && t6 <= 0xC) {
                        v[6] = (byte) (t6 - 6);
                    } else if (t6 >= 0x2 && t6 <= 0x6) {
                        v[6] = (byte) (t6 + 6);
                    } else if (t6 == 0x1C) {
                        v[6]++;
                    } else if (t6 == 0x1D) {
                        v[6]--;
                    }
                }
                if (kataHira) {
                    int t6 = v[6] & 0xFF;
                    if (t6 >= 0x0F && t6 <= 0x13) {
                        v[6] = (byte) (t6 - 2);
                    } else if (t6 >= 0xD && t6 <= 0xE) {
                        v[6] = (byte) (t6 + 5);
                    }
                }
            }

            for (int lv = 0; lv < level; lv++) {
                int o1 = 2 * lv + 1;
                int o2 = 2 * lv + 2;
                if ((v[o1] & 0xFF) != 0 || (v[o2] & 0xFF) != 0) {
                    eachLevel[lv][sPtr[lv][0]++] = v[o1];
                    eachLevel[lv][sPtr[lv][0]++] = v[o2];
                }
            }
        }

        int dlen = 2 * (MAX_LEVEL - 1);
        for (int lv = 0; lv < level; lv++) {
            dlen += sPtr[lv][0];
        }
        byte[] dst = new byte[dlen + 1];

        RuntimeScalar bfSv = hashGet(selfHV, "backwardsFlag");
        long backFlag = (bfSv != null && bfSv.getDefinedBoolean()) ? bfSv.getUnsignedLong().longValue() : 0L;

        int di = 0;
        for (int lv = 0; lv < level; lv++) {
            int end = sPtr[lv][0];
            if ((backFlag & (1L << (lv + 1))) != 0) {
                for (int p = end; p >= 2; p -= 2) {
                    dst[di++] = eachLevel[lv][p - 2];
                    dst[di++] = eachLevel[lv][p - 1];
                }
            } else {
                for (int p = 0; p < end; p++) {
                    dst[di++] = eachLevel[lv][p];
                }
            }
            if (lv + 1 < MAX_LEVEL) {
                dst[di++] = 0;
                dst[di++] = 0;
            }
        }
        for (int lv = level; lv < MAX_LEVEL; lv++) {
            if (lv + 1 < MAX_LEVEL) {
                dst[di++] = 0;
                dst[di++] = 0;
            }
        }
        return new RuntimeList(new RuntimeScalar(Arrays.copyOf(dst, di)));
    }

    private static byte[] latin1Bytes(RuntimeScalar cell) {
        if (cell.type == RuntimeScalarType.BYTE_STRING) {
            return cell.toString().getBytes(StandardCharsets.ISO_8859_1);
        }
        String s = cell.toString();
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** XS: varCE */
    public static RuntimeList varCE(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeList(new RuntimeScalar(new byte[0]));
        }
        RuntimeHash selfHV = expectHashRef(args.get(0), "varCE");
        RuntimeScalar vceSv = args.get(1);

        RuntimeScalar igSv = hashGet(selfHV, "ignore_level2");
        boolean igL2 = igSv != null && igSv.getDefinedBoolean() && igSv.getBoolean();

        RuntimeScalar varSv = hashGet(selfHV, "variable");
        byte[] vbl = varSv != null && varSv.getDefinedBoolean()
                ? latin1Bytes(varSv)
                : new byte[]{'n'};
        byte[] v = latin1Bytes(vceSv);
        if (v.length == 0) {
            return new RuntimeList(new RuntimeScalar(new byte[0]));
        }
        byte[] d = Arrays.copyOf(v, v.length);

        if (igL2 && (d[1] & 0xFF) == 0 && (d[2] & 0xFF) == 0 && ((d[3] & 0xFF) != 0 || (d[4] & 0xFF) != 0)) {
            d[3] = d[4] = d[5] = d[6] = 0;
        }

        if (vbl.length >= 1 && vbl[0] != 'n' && v.length >= VCE_LENGTH) {
            if (d[0] != 0) {
                if (vbl[0] == 's') {
                    d[7] = d[1];
                    d[8] = d[2];
                }
                d[1] = d[2] = d[3] = d[4] = d[5] = d[6] = 0;
            } else if (vbl[0] == 's') {
                int totwt = (d[1] & 0xFF) + (d[2] & 0xFF) + (d[3] & 0xFF) + (d[4] & 0xFF) + (d[5] & 0xFF) + (d[6] & 0xFF);
                if (vbl.length == 7 && totwt != 0) {
                    if ((d[1] & 0xFF) == 0 && (d[2] & 0xFF) == 1) {
                        d[7] = d[1];
                        d[8] = d[2];
                    } else {
                        RuntimeScalar ucaSv = hashGet(selfHV, "UCA_Version");
                        if (ucaSv == null || !ucaSv.getDefinedBoolean()) {
                            WarnDie.die(new RuntimeScalar("Panic: no $self->{UCA_Version} in varCE\n"), new RuntimeScalar("\n"));
                        }
                        int ucaVers = ucaSv.getInt();
                        if (ucaVers >= 36 && ((d[3] & 0xFF) + (d[4] & 0xFF) + (d[5] & 0xFF) + (d[6] & 0xFF) == 0)) {
                            d[7] = d[8] = 0;
                        } else {
                            d[7] = (byte) ((SHIFT_4_WT >> 8) & 0xFF);
                            d[8] = (byte) (SHIFT_4_WT & 0xFF);
                        }
                    }
                } else {
                    d[7] = d[8] = 0;
                }
            }
        }
        return new RuntimeList(new RuntimeScalar(d));
    }

    /** XS: visualizeSortKey */
    public static RuntimeList visualizeSortKey(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeList(new RuntimeScalar("[]"));
        }
        RuntimeHash selfHV = expectHashRef(args.get(0), "visualizeSortKey");
        RuntimeScalar keySv = args.get(1);
        RuntimeScalar ucaSv = hashGet(selfHV, "UCA_Version");
        if (ucaSv == null || !ucaSv.getDefinedBoolean()) {
            WarnDie.die(new RuntimeScalar("Panic: no $self->{UCA_Version} in visualizeSortKey\n"), new RuntimeScalar("\n"));
        }
        int ucaVers = ucaSv.getInt();
        byte[] s = latin1Bytes(keySv);
        int klen = s.length;
        String upperHex = "0123456789ABCDEF";
        StringBuilder out = new StringBuilder((klen / 2) * 5 + MAX_LEVEL * 2 + 2);
        out.append('[');
        int sep = 0;
        for (int si = 0; si + 1 < klen; si += 2) {
            int uv = ((s[si] & 0xFF) << 8) | (s[si + 1] & 0xFF);
            if (uv != 0 || sep >= MAX_LEVEL) {
                if (out.charAt(out.length() - 1) != '[' && (ucaVers >= 9 || out.charAt(out.length() - 1) != '|')) {
                    out.append(' ');
                }
                out.append(upperHex.charAt((s[si] >> 4) & 0xF));
                out.append(upperHex.charAt(s[si] & 0xF));
                out.append(upperHex.charAt((s[si + 1] >> 4) & 0xF));
                out.append(upperHex.charAt(s[si + 1] & 0xF));
            } else {
                if (ucaVers >= 9 && out.charAt(out.length() - 1) != '[') {
                    out.append(' ');
                }
                out.append('|');
                sep++;
            }
        }
        out.append(']');
        return new RuntimeList(new RuntimeScalar(out.toString()));
    }
}
