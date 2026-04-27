package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.BYTE_STRING;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.STRING;

/**
 * The Encode module for PerlOnJava.
 * Provides character encoding/decoding functionality similar to Perl's Encode module.
 * Uses Java's java.nio.charset API for encoding support.
 */
public class Encode extends PerlModuleBase {

    private static final Map<String, Charset> CHARSET_ALIASES = new HashMap<>();

    // Encode check-flag bit constants (from Perl's encode.h)
    // These are bitmask values used by the $check parameter.
    private static final int DIE_ON_ERR = 0x0001;          // Croak on error
    private static final int WARN_ON_ERR = 0x0002;         // Warn on error
    private static final int RETURN_ON_ERR = 0x0004;       // Return on error (don't die)
    private static final int LEAVE_SRC = 0x0008;           // Don't modify source
    private static final int ONLY_PRAGMA_WARNINGS = 0x0010; // Use lexical warnings only
    private static final int PERLQQ = 0x0100;              // \x{HHHH} substitution
    private static final int HTMLCREF = 0x0200;            // &#DDDD; substitution
    private static final int XMLCREF = 0x0400;             // &#xHHHH; substitution
    private static final int STOP_AT_PARTIAL = 0x0800;     // Stop at partial character

    // Composite fallback constants
    private static final int FB_DEFAULT_VAL = 0;                                     // 0
    private static final int FB_CROAK_VAL = DIE_ON_ERR;                              // 1
    private static final int FB_QUIET_VAL = RETURN_ON_ERR;                           // 4
    private static final int FB_WARN_VAL = RETURN_ON_ERR | WARN_ON_ERR;             // 6
    private static final int FB_PERLQQ_VAL = PERLQQ | LEAVE_SRC;                    // 264
    private static final int FB_HTMLCREF_VAL = HTMLCREF | LEAVE_SRC;                // 520
    private static final int FB_XMLCREF_VAL = XMLCREF | LEAVE_SRC;                  // 1032

    static {
        // Initialize common charset aliases
        CHARSET_ALIASES.put("utf8", StandardCharsets.UTF_8);
        CHARSET_ALIASES.put("UTF8", StandardCharsets.UTF_8);
        CHARSET_ALIASES.put("utf-8", StandardCharsets.UTF_8);
        CHARSET_ALIASES.put("UTF-8", StandardCharsets.UTF_8);
        // Perl's internal UTF-8 encoding (loose)
        CHARSET_ALIASES.put("utf-8-strict", StandardCharsets.UTF_8);

        CHARSET_ALIASES.put("latin1", StandardCharsets.ISO_8859_1);
        CHARSET_ALIASES.put("Latin1", StandardCharsets.ISO_8859_1);
        CHARSET_ALIASES.put("latin-1", StandardCharsets.ISO_8859_1);
        CHARSET_ALIASES.put("Latin-1", StandardCharsets.ISO_8859_1);
        CHARSET_ALIASES.put("iso-8859-1", StandardCharsets.ISO_8859_1);
        CHARSET_ALIASES.put("ISO-8859-1", StandardCharsets.ISO_8859_1);

        CHARSET_ALIASES.put("ascii", StandardCharsets.US_ASCII);
        CHARSET_ALIASES.put("ASCII", StandardCharsets.US_ASCII);
        CHARSET_ALIASES.put("us-ascii", StandardCharsets.US_ASCII);
        CHARSET_ALIASES.put("US-ASCII", StandardCharsets.US_ASCII);

        CHARSET_ALIASES.put("utf16", StandardCharsets.UTF_16);
        CHARSET_ALIASES.put("UTF16", StandardCharsets.UTF_16);
        CHARSET_ALIASES.put("utf-16", StandardCharsets.UTF_16);
        CHARSET_ALIASES.put("UTF-16", StandardCharsets.UTF_16);

        CHARSET_ALIASES.put("utf16be", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("UTF16BE", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("utf-16be", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("UTF-16BE", StandardCharsets.UTF_16BE);

        CHARSET_ALIASES.put("utf16le", StandardCharsets.UTF_16LE);
        CHARSET_ALIASES.put("UTF16LE", StandardCharsets.UTF_16LE);
        CHARSET_ALIASES.put("utf-16le", StandardCharsets.UTF_16LE);
        CHARSET_ALIASES.put("UTF-16LE", StandardCharsets.UTF_16LE);

        // UCS-2 (Perl's Encode maps "ucs2" to UCS-2BE, equivalent to UTF-16BE for BMP)
        CHARSET_ALIASES.put("ucs2", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("UCS2", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("ucs-2", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("UCS-2", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("UCS-2BE", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("ucs-2be", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("UCS-2LE", StandardCharsets.UTF_16LE);
        CHARSET_ALIASES.put("ucs-2le", StandardCharsets.UTF_16LE);

        // Shift_JIS aliases
        try {
            Charset shiftJIS = Charset.forName("Shift_JIS");
            CHARSET_ALIASES.put("sjis", shiftJIS);
            CHARSET_ALIASES.put("SJIS", shiftJIS);
            CHARSET_ALIASES.put("shiftjis", shiftJIS);
            CHARSET_ALIASES.put("shift-jis", shiftJIS);
        } catch (Exception ignored) {
            // Shift_JIS may not be available on all JVMs
        }

        // EUC-JP alias
        try {
            Charset eucJP = Charset.forName("EUC-JP");
            CHARSET_ALIASES.put("euc-jp", eucJP);
            CHARSET_ALIASES.put("eucjp", eucJP);
        } catch (Exception ignored) {
        }

        // "locale" and "locale_fs" - map to JVM's default charset.
        // Encode::Locale registers these via Encode::Alias, but the Java decode/encode
        // methods bypass Perl-side alias resolution. The JVM default charset matches
        // what Encode::Locale detects from the OS locale (e.g. UTF-8 on modern systems).
        Charset defaultCharset = Charset.defaultCharset();
        CHARSET_ALIASES.put("locale", defaultCharset);
        CHARSET_ALIASES.put("locale_fs", defaultCharset);

        // UTF-32 aliases
        try {
            Charset utf32 = Charset.forName("UTF-32");
            CHARSET_ALIASES.put("utf32", utf32);
            CHARSET_ALIASES.put("UTF32", utf32);
            CHARSET_ALIASES.put("utf-32", utf32);
            CHARSET_ALIASES.put("UTF-32", utf32);
        } catch (Exception ignored) {
        }
        try {
            Charset utf32be = Charset.forName("UTF-32BE");
            CHARSET_ALIASES.put("utf32be", utf32be);
            CHARSET_ALIASES.put("UTF32BE", utf32be);
            CHARSET_ALIASES.put("utf-32be", utf32be);
            CHARSET_ALIASES.put("UTF-32BE", utf32be);
            CHARSET_ALIASES.put("UTF32-BE", utf32be);
        } catch (Exception ignored) {
        }
        try {
            Charset utf32le = Charset.forName("UTF-32LE");
            CHARSET_ALIASES.put("utf32le", utf32le);
            CHARSET_ALIASES.put("UTF32LE", utf32le);
            CHARSET_ALIASES.put("utf-32le", utf32le);
            CHARSET_ALIASES.put("UTF-32LE", utf32le);
            CHARSET_ALIASES.put("UTF32-LE", utf32le);
        } catch (Exception ignored) {
        }
    }

    public Encode() {
        super("Encode", false);  // Don't set %INC - let Encode.pm run via XSLoader
    }

    public static void initialize() {
        Encode encode = new Encode();
        // Set $VERSION so CPAN.pm can detect our bundled version
        GlobalVariable.getGlobalVariable("Encode::VERSION").set(new RuntimeScalar("3.21"));
        encode.initializeExporter();
        encode.defineExport("EXPORT", "encode", "decode", "encode_utf8", "decode_utf8",
                "is_utf8", "find_encoding", "from_to");
        encode.defineExport("EXPORT_OK", "FB_CROAK", "FB_QUIET", "FB_WARN", "FB_PERLQQ",
                "FB_HTMLCREF", "FB_XMLCREF", "PERLQQ", "HTMLCREF", "XMLCREF",
                "DIE_ON_ERR", "WARN_ON_ERR", "RETURN_ON_ERR", "LEAVE_SRC",
                "ONLY_PRAGMA_WARNINGS", "STOP_AT_PARTIAL",
                "FB_DEFAULT", "encode", "decode", "encode_utf8", "decode_utf8",
                "is_utf8", "find_encoding", "from_to", "_utf8_on", "_utf8_off",
                "define_encoding", "encodings", "perlio_ok", "resolve_alias",
                "find_mime_encoding");
        encode.defineExportTag("fallbacks",
                "FB_DEFAULT", "FB_CROAK", "FB_QUIET", "FB_WARN",
                "FB_PERLQQ", "FB_HTMLCREF", "FB_XMLCREF");
        encode.defineExportTag("fallback_all",
                "FB_DEFAULT", "FB_CROAK", "FB_QUIET", "FB_WARN",
                "FB_PERLQQ", "FB_HTMLCREF", "FB_XMLCREF",
                "LEAVE_SRC", "DIE_ON_ERR", "WARN_ON_ERR", "RETURN_ON_ERR",
                "PERLQQ", "HTMLCREF", "XMLCREF",
                "STOP_AT_PARTIAL", "ONLY_PRAGMA_WARNINGS");
        // :default and :all — parity with core Encode.pm.
        // Built from the @EXPORT / @EXPORT_OK lists already pushed above so
        // any module doing `use Encode qw(:all)` or qw(:default) works.
        encode.defineDefaultAndAllTags();
        try {
            encode.registerMethod("encode", null);
            encode.registerMethod("decode", null);
            encode.registerMethod("encode_utf8", null);
            encode.registerMethod("decode_utf8", null);
            encode.registerMethod("is_utf8", null);
            encode.registerMethod("find_encoding", null);
            encode.registerMethod("from_to", null);
            encode.registerMethod("_utf8_on", null);
            encode.registerMethod("_utf8_off", null);
            // Register constants
            encode.registerMethod("FB_DEFAULT", null);
            encode.registerMethod("FB_CROAK", null);
            encode.registerMethod("FB_QUIET", null);
            encode.registerMethod("FB_WARN", null);
            encode.registerMethod("FB_PERLQQ", null);
            encode.registerMethod("FB_HTMLCREF", null);
            encode.registerMethod("FB_XMLCREF", null);
            encode.registerMethod("PERLQQ", null);
            encode.registerMethod("HTMLCREF", null);
            encode.registerMethod("XMLCREF", null);
            encode.registerMethod("DIE_ON_ERR", null);
            encode.registerMethod("WARN_ON_ERR", null);
            encode.registerMethod("RETURN_ON_ERR", null);
            encode.registerMethod("LEAVE_SRC", null);
            encode.registerMethod("ONLY_PRAGMA_WARNINGS", null);
            encode.registerMethod("STOP_AT_PARTIAL", null);
            encode.registerMethod("define_encoding", null);
            encode.registerMethod("encodings", null);
            encode.registerMethod("resolve_alias", null);
            encode.registerMethod("perlio_ok", null);
            encode.registerMethod("find_mime_encoding", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Encode method: " + e.getMessage());
        }

        // Register Encode::Encoding instance methods for find_encoding() objects
        try {
            java.lang.invoke.MethodHandle encodeHandle = RuntimeCode.lookup.findStatic(
                    Encode.class, "encoding_encode", RuntimeCode.methodType);
            java.lang.invoke.MethodHandle decodeHandle = RuntimeCode.lookup.findStatic(
                    Encode.class, "encoding_decode", RuntimeCode.methodType);
            java.lang.invoke.MethodHandle nameHandle = RuntimeCode.lookup.findStatic(
                    Encode.class, "encoding_name", RuntimeCode.methodType);
            RuntimeCode encodeCode = new RuntimeCode(encodeHandle, null, null);
            encodeCode.isStatic = true;
            RuntimeCode decodeCode = new RuntimeCode(decodeHandle, null, null);
            decodeCode.isStatic = true;
            RuntimeCode nameCode = new RuntimeCode(nameHandle, null, null);
            nameCode.isStatic = true;
            GlobalVariable.getGlobalCodeRef("Encode::Encoding::encode").set(
                    new RuntimeScalar(encodeCode));
            GlobalVariable.getGlobalCodeRef("Encode::Encoding::decode").set(
                    new RuntimeScalar(decodeCode));
            GlobalVariable.getGlobalCodeRef("Encode::Encoding::name").set(
                    new RuntimeScalar(nameCode));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            System.err.println("Warning: Missing Encode::Encoding method: " + e.getMessage());
        }
    }

    // --- Constant accessor methods ---

    public static RuntimeList FB_DEFAULT(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_DEFAULT_VAL).getList();
    }

    public static RuntimeList FB_CROAK(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_CROAK_VAL).getList();
    }

    public static RuntimeList FB_QUIET(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_QUIET_VAL).getList();
    }

    public static RuntimeList FB_WARN(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_WARN_VAL).getList();
    }

    public static RuntimeList FB_PERLQQ(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_PERLQQ_VAL).getList();
    }

    public static RuntimeList FB_HTMLCREF(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_HTMLCREF_VAL).getList();
    }

    public static RuntimeList FB_XMLCREF(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_XMLCREF_VAL).getList();
    }

    public static RuntimeList PERLQQ(RuntimeArray args, int ctx) {
        return new RuntimeScalar(PERLQQ).getList();
    }

    public static RuntimeList HTMLCREF(RuntimeArray args, int ctx) {
        return new RuntimeScalar(HTMLCREF).getList();
    }

    public static RuntimeList XMLCREF(RuntimeArray args, int ctx) {
        return new RuntimeScalar(XMLCREF).getList();
    }

    public static RuntimeList DIE_ON_ERR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(DIE_ON_ERR).getList();
    }

    public static RuntimeList WARN_ON_ERR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(WARN_ON_ERR).getList();
    }

    public static RuntimeList RETURN_ON_ERR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(RETURN_ON_ERR).getList();
    }

    public static RuntimeList LEAVE_SRC(RuntimeArray args, int ctx) {
        return new RuntimeScalar(LEAVE_SRC).getList();
    }

    public static RuntimeList ONLY_PRAGMA_WARNINGS(RuntimeArray args, int ctx) {
        return new RuntimeScalar(ONLY_PRAGMA_WARNINGS).getList();
    }

    public static RuntimeList STOP_AT_PARTIAL(RuntimeArray args, int ctx) {
        return new RuntimeScalar(STOP_AT_PARTIAL).getList();
    }

    /**
     * define_encoding($obj, $name, ...) - registers an encoding object.
     */
    public static RuntimeList define_encoding(RuntimeArray args, int ctx) {
        // Register the encoding object in %Encode::Encoding hash
        if (args.size() >= 2) {
            RuntimeScalar obj = args.get(0);
            // Register under all provided names
            RuntimeHash encodingHash = GlobalVariable.getGlobalHash("Encode::Encoding");
            for (int i = 1; i < args.size(); i++) {
                String name = args.get(i).toString();
                encodingHash.put(name, obj);
            }
            return obj.getList();
        }
        return new RuntimeScalar().getList();
    }

    /**
     * encodings([$class]) - returns a list of available encoding names.
     * Returns all encodings from both the Java Charset API and the CHARSET_ALIASES map.
     */
    public static RuntimeList encodings(RuntimeArray args, int ctx) {
        RuntimeList list = new RuntimeList();
        Set<String> names = new TreeSet<>();

        // Add Perl-style canonical names from our alias map
        names.add("ascii");
        names.add("utf8");
        names.add("utf-8-strict");

        // Add all available Java charsets
        for (Map.Entry<String, Charset> entry : Charset.availableCharsets().entrySet()) {
            String name = entry.getKey();
            names.add(name);
            // Also add aliases
            for (String alias : entry.getValue().aliases()) {
                names.add(alias);
            }
        }

        for (String name : names) {
            list.add(new RuntimeScalar(name));
        }
        return list;
    }

    /**
     * perlio_ok($encoding) - checks if encoding can be used with PerlIO layers.
     * Returns 0 for now (PerlIO encoding layers not fully supported on JVM).
     */
    public static RuntimeList perlio_ok(RuntimeArray args, int ctx) {
        return scalarFalse.getList();
    }

    /**
     * resolve_alias($name) - resolves an encoding alias to a canonical name.
     */
    public static RuntimeList resolve_alias(RuntimeArray args, int ctx) {
        if (args.size() > 0) {
            String name = args.get(0).toString();
            try {
                Charset cs = getCharset(name);
                return new RuntimeScalar(cs.name()).getList();
            } catch (Exception e) {
                // Fall through to return undef
            }
        }
        return scalarUndef.getList();
    }

    // --- Helper: parse the $check parameter ---

    /**
     * Parses the $check argument into an integer bitmask.
     * If $check is undef or not provided, returns FB_DEFAULT (0).
     * If $check is a CODE reference, returns FB_WARN_VAL as default behavior
     * (the coderef is extracted separately via getCheckCodeRef).
     */
    private static int parseCheck(RuntimeArray args, int checkArgIndex) {
        if (args.size() > checkArgIndex) {
            RuntimeScalar checkArg = args.get(checkArgIndex);
            if (checkArg.getDefinedBoolean()) {
                // Check if $check is a CODE reference (coderef fallback)
                if (checkArg.type == RuntimeScalarType.CODE) {
                    // Coderef implies RETURN_ON_ERR behavior with custom handling
                    return RETURN_ON_ERR | LEAVE_SRC;
                }
                return checkArg.getInt();
            }
        }
        return FB_DEFAULT_VAL;
    }

    /**
     * Extracts a CODE reference from the $check argument, or null if not a coderef.
     */
    private static RuntimeScalar getCheckCodeRef(RuntimeArray args, int checkArgIndex) {
        if (args.size() > checkArgIndex) {
            RuntimeScalar checkArg = args.get(checkArgIndex);
            if (checkArg.type == RuntimeScalarType.CODE) {
                return checkArg;
            }
        }
        return null;
    }

    /**
     * Handles an encoding error according to the $check flags.
     * Returns the replacement string for the unmappable character, or null to skip it.
     * Throws PerlCompilerException for FB_CROAK.
     */
    private static String handleEncodingError(int check, RuntimeScalar codeRef, int codePoint, String encodingName, boolean isEncode) {
        return handleEncodingError(check, codeRef, new int[]{codePoint}, encodingName, isEncode);
    }

    /**
     * Handles an encoding error according to the $check flags.
     * For decode errors, codePoints may contain multiple bad bytes.
     * Returns the replacement string for the unmappable character(s), or null to skip.
     * Throws PerlCompilerException for FB_CROAK.
     */
    private static String handleEncodingError(int check, RuntimeScalar codeRef, int[] codePoints, String encodingName, boolean isEncode) {
        // If a coderef fallback is provided, call it
        if (codeRef != null) {
            RuntimeArray cbArgs = new RuntimeArray();
            if (isEncode) {
                // For encode: pass the unmappable codepoint
                cbArgs.push(new RuntimeScalar(codePoints[0]));
            } else {
                // For decode: pass each bad byte as separate arg
                for (int cp : codePoints) {
                    cbArgs.push(new RuntimeScalar(cp & 0xFF));
                }
            }
            RuntimeList result = RuntimeCode.apply(codeRef, cbArgs, RuntimeContextType.SCALAR);
            return result.scalar().toString();
        }

        // Check DIE_ON_ERR (FB_CROAK)
        if ((check & DIE_ON_ERR) != 0) {
            if (isEncode) {
                throw new PerlCompilerException("\"\\x{" + Integer.toHexString(codePoints[0])
                        + "}\" does not map to " + encodingName);
            } else {
                StringBuilder hexBytes = new StringBuilder();
                for (int cp : codePoints) {
                    hexBytes.append("\\x").append(String.format("%02X", cp & 0xFF));
                }
                throw new PerlCompilerException("" + encodingName + " \""
                        + hexBytes + "\" does not map to Unicode");
            }
        }

        // Check WARN_ON_ERR (FB_WARN)
        if ((check & WARN_ON_ERR) != 0) {
            String warnMsg;
            if (isEncode) {
                warnMsg = "\"\\x{" + Integer.toHexString(codePoints[0])
                        + "}\" does not map to " + encodingName;
            } else {
                StringBuilder hexBytes = new StringBuilder();
                for (int cp : codePoints) {
                    hexBytes.append("\\x").append(String.format("%02X", cp & 0xFF));
                }
                warnMsg = "" + encodingName + " \""
                        + hexBytes + "\" does not map to Unicode";
            }
            // Use Perl's warn mechanism so $SIG{__WARN__} can intercept.
            // When ONLY_PRAGMA_WARNINGS is set, check lexical 'utf8' warning scope;
            // otherwise always emit the warning regardless of lexical scope.
            if ((check & ONLY_PRAGMA_WARNINGS) != 0) {
                WarnDie.warnWithCategory(new RuntimeScalar(warnMsg), new RuntimeScalar(""), "utf8");
            } else {
                WarnDie.warn(new RuntimeScalar(warnMsg), new RuntimeScalar(""));
            }
        }

        // Check substitution modes
        if ((check & PERLQQ) != 0) {
            if (isEncode) {
                String hex = String.format("%04X", codePoints[0]);
                return "\\x{" + hex + "}";
            } else {
                StringBuilder sb = new StringBuilder();
                for (int cp : codePoints) {
                    sb.append("\\x").append(String.format("%02X", cp & 0xFF));
                }
                return sb.toString();
            }
        }
        if ((check & HTMLCREF) != 0) {
            return "&#" + codePoints[0] + ";";
        }
        if ((check & XMLCREF) != 0) {
            return "&#x" + Integer.toHexString(codePoints[0]) + ";";
        }

        // RETURN_ON_ERR (FB_QUIET): stop processing, return what we have so far
        if ((check & RETURN_ON_ERR) != 0) {
            return null; // Signal to stop processing
        }

        // FB_DEFAULT: substitute with replacement character and continue
        return isEncode ? "?" : "\uFFFD";
    }

    // --- Core encode/decode methods ---

    /**
     * encode($encoding, $string [, $check])
     * Encodes a string from Perl's internal format to the specified encoding.
     * $encoding can be a string name or a blessed encoding object.
     */
    public static RuntimeList encode(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("Bad number of arguments for encode");
        }

        // Return undef if input string is undef
        if (!args.get(1).getDefinedBoolean()) {
            return scalarUndef.getList();
        }

        RuntimeScalar encodingArg = args.get(0);

        // Check if the encoding argument is already a blessed encoding object
        if (RuntimeScalarType.isReference(encodingArg)) {
            return dispatchToEncodingObject(encodingArg, "encode", args, 1, 2, ctx);
        }

        // Check %Encode::Encoding for Perl-registered encodings
        String encodingName = encodingArg.toString();
        RuntimeScalar perlEncObj = lookupPerlEncoding(encodingName);
        if (perlEncObj != null) {
            return dispatchToEncodingObject(perlEncObj, "encode", args, 1, 2, ctx);
        }

        String string = args.get(1).toString();
        int check = parseCheck(args, 2);
        RuntimeScalar codeRef = getCheckCodeRef(args, 2);

        Charset charset = getCharset(encodingName);

        if (check == FB_DEFAULT_VAL) {
            // Fast path: no error handling, use Java's default replacement
            byte[] bytes = string.getBytes(charset);
            return new RuntimeScalar(bytes).getList();
        }

        // Use shared encode helper
        return encodeWithCharset(string, charset, encodingName, check, codeRef, args, 1).getList();
    }

    /**
     * Shared encode logic used by both encode() and encoding_encode().
     * Returns a BYTE_STRING RuntimeScalar.
     */
    private static RuntimeScalar encodeWithCharset(String string, Charset charset, String encodingName,
                                                    int check, RuntimeScalar codeRef,
                                                    RuntimeArray srcArgs, int srcArgIndex) {
        CharsetEncoder encoder = charset.newEncoder();
        encoder.onMalformedInput(CodingErrorAction.REPORT);
        encoder.onUnmappableCharacter(CodingErrorAction.REPORT);

        StringBuilder result = new StringBuilder();
        CharBuffer input = CharBuffer.wrap(string);
        ByteBuffer output = ByteBuffer.allocate((int) (string.length() * encoder.maxBytesPerChar()) + 4);

        while (input.hasRemaining()) {
            encoder.reset();
            CoderResult cr = encoder.encode(input, output, true);
            // Flush any buffered output
            output.flip();
            byte[] chunk = new byte[output.remaining()];
            output.get(chunk);
            for (byte b : chunk) {
                result.append((char) (b & 0xFF));
            }
            output.clear();

            if (cr.isUnmappable() || cr.isMalformed()) {
                int badChar = input.get(); // consume the bad character
                String replacement = handleEncodingError(check, codeRef, badChar, encodingName, true);
                if (replacement == null) {
                    // FB_QUIET: stop processing, put back unprocessed chars
                    if ((check & LEAVE_SRC) == 0 && srcArgs != null && srcArgs.size() > srcArgIndex) {
                        StringBuilder remaining = new StringBuilder();
                        remaining.append((char) badChar);
                        while (input.hasRemaining()) {
                            remaining.append(input.get());
                        }
                        srcArgs.get(srcArgIndex).set(remaining.toString());
                    }
                    break;
                }
                for (int i = 0; i < replacement.length(); i++) {
                    result.append(replacement.charAt(i));
                }
            } else if (cr.isOverflow()) {
                output = ByteBuffer.allocate(output.capacity() * 2);
            }
        }

        // Flush encoder
        encoder.reset();
        output.clear();
        encoder.encode(CharBuffer.allocate(0), output, true);
        encoder.flush(output);
        output.flip();
        while (output.hasRemaining()) {
            result.append((char) (output.get() & 0xFF));
        }

        // Build BYTE_STRING result
        RuntimeScalar resultScalar = new RuntimeScalar();
        resultScalar.type = BYTE_STRING;
        resultScalar.value = result.toString();

        // Update source if LEAVE_SRC is not set (remove processed chars)
        if ((check & LEAVE_SRC) == 0 && (check & RETURN_ON_ERR) == 0
                && srcArgs != null && srcArgs.size() > srcArgIndex) {
            srcArgs.get(srcArgIndex).set("");
        }

        return resultScalar;
    }

    /**
     * decode($encoding, $octets [, $check])
     * Decodes a string from the specified encoding to Perl's internal format.
     * $encoding can be a string name or a blessed encoding object.
     */
    public static RuntimeList decode(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("Bad number of arguments for decode");
        }

        // Return undef if input octets is undef
        if (!args.get(1).getDefinedBoolean()) {
            return scalarUndef.getList();
        }

        RuntimeScalar encodingArg = args.get(0);

        // Check if the encoding argument is already a blessed encoding object
        if (RuntimeScalarType.isReference(encodingArg)) {
            return dispatchToEncodingObject(encodingArg, "decode", args, 1, 2, ctx);
        }

        // Check %Encode::Encoding for Perl-registered encodings
        String encodingName = encodingArg.toString();
        RuntimeScalar perlEncObj = lookupPerlEncoding(encodingName);
        if (perlEncObj != null) {
            return dispatchToEncodingObject(perlEncObj, "decode", args, 1, 2, ctx);
        }

        String octets = args.get(1).toString();
        int check = parseCheck(args, 2);
        RuntimeScalar codeRef = getCheckCodeRef(args, 2);

        Charset charset = getCharset(encodingName);

        // Convert the string to bytes assuming it contains raw octets
        byte[] bytes = octets.getBytes(StandardCharsets.ISO_8859_1);
        // Trim orphan trailing bytes for fixed-width encodings
        bytes = trimOrphanBytes(bytes, charset);

        if (check == FB_DEFAULT_VAL) {
            // Fast path: no error handling
            String decoded = new String(bytes, charset);
            return new RuntimeScalar(decoded).getList();
        }

        // Use shared decode helper
        return decodeWithCharset(bytes, charset, encodingName, check, codeRef, args, 1).getList();
    }

    /**
     * Shared decode logic used by both decode() and encoding_decode().
     * Returns a STRING RuntimeScalar.
     */
    private static RuntimeScalar decodeWithCharset(byte[] bytes, Charset charset, String encodingName,
                                                    int check, RuntimeScalar codeRef,
                                                    RuntimeArray srcArgs, int srcArgIndex) {
        CharsetDecoder decoder = charset.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);

        ByteBuffer input = ByteBuffer.wrap(bytes);
        CharBuffer output = CharBuffer.allocate(bytes.length * 2 + 4);
        StringBuilder result = new StringBuilder();

        while (input.hasRemaining()) {
            decoder.reset();
            CoderResult cr = decoder.decode(input, output, true);
            output.flip();
            result.append(output);
            output.clear();

            if (cr.isMalformed() || cr.isUnmappable()) {
                int malformedLen = cr.length();
                // Collect all malformed/unmappable bytes
                int[] badBytes = new int[malformedLen];
                for (int i = 0; i < malformedLen; i++) {
                    badBytes[i] = input.get() & 0xFF;
                }
                String replacement = handleEncodingError(check, codeRef, badBytes, encodingName, false);
                if (replacement == null) {
                    // FB_QUIET: stop processing
                    if ((check & LEAVE_SRC) == 0 && srcArgs != null && srcArgs.size() > srcArgIndex) {
                        byte[] remaining = new byte[input.remaining() + malformedLen];
                        for (int i = 0; i < malformedLen; i++) {
                            remaining[i] = (byte) badBytes[i];
                        }
                        input.get(remaining, malformedLen, input.remaining());
                        srcArgs.get(srcArgIndex).set(new String(remaining, StandardCharsets.ISO_8859_1));
                        srcArgs.get(srcArgIndex).type = BYTE_STRING;
                    }
                    break;
                }
                result.append(replacement);
            } else if (cr.isOverflow()) {
                output = CharBuffer.allocate(output.capacity() * 2);
            }
        }

        // Flush decoder
        decoder.reset();
        output.clear();
        decoder.decode(ByteBuffer.allocate(0), output, true);
        decoder.flush(output);
        output.flip();
        result.append(output);

        // Update source if LEAVE_SRC is not set
        if ((check & LEAVE_SRC) == 0 && (check & RETURN_ON_ERR) == 0
                && srcArgs != null && srcArgs.size() > srcArgIndex) {
            srcArgs.get(srcArgIndex).set("");
        }

        return new RuntimeScalar(result.toString());
    }

    /**
     * encode_utf8($string)
     * Equivalent to encode("utf8", $string)
     */
    public static RuntimeList encode_utf8(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("Bad number of arguments for encode_utf8");
        }

        // Return undef if input is undef
        if (!args.get(0).getDefinedBoolean()) {
            return scalarUndef.getList();
        }

        String string = args.get(0).toString();
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);

        // Return the encoded bytes as a byte string
        return new RuntimeScalar(bytes).getList();
    }

    /**
     * decode_utf8($octets [, $check])
     * Equivalent to decode("utf8", $octets [, $check])
     */
    public static RuntimeList decode_utf8(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("Bad number of arguments for decode_utf8");
        }

        // Return undef if input is undef
        if (!args.get(0).getDefinedBoolean()) {
            return scalarUndef.getList();
        }

        String octets = args.get(0).toString();
        int check = parseCheck(args, 1);

        // Convert the string to bytes assuming it contains raw octets
        byte[] bytes = octets.getBytes(StandardCharsets.ISO_8859_1);

        if (check == FB_DEFAULT_VAL) {
            // Fast path
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            return new RuntimeScalar(decoded).getList();
        }

        // Slow path with error handling - delegate to decode()
        RuntimeArray decodeArgs = new RuntimeArray();
        decodeArgs.push(new RuntimeScalar("utf-8-strict"));
        decodeArgs.push(args.get(0));
        if (args.size() > 1) {
            decodeArgs.push(args.get(1));
        }
        return decode(decodeArgs, ctx);
    }

    /**
     * is_utf8($string [, $check])
     * Tests whether the UTF8 flag is turned on in the string.
     * In Perl, this simply checks the SvUTF8 flag, not the content.
     * If $check is true, also validates the string is well-formed UTF-8.
     */
    public static RuntimeList is_utf8(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("Bad number of arguments for is_utf8");
        }

        RuntimeScalar arg = args.get(0);

        // Check the UTF-8 flag: only STRING type has it set.
        // INTEGER, DOUBLE, UNDEF, REFERENCE etc. don't have the UTF-8 flag in Perl.
        boolean hasUtf8Flag = (arg.type == STRING);

        if (!hasUtf8Flag) {
            return scalarFalse.getList();
        }

        // If $check is provided and true, validate the string is well-formed UTF-8
        if (args.size() > 1 && args.get(1).getBoolean()) {
            String s = arg.toString();
            // Check that the string is valid (no surrogates, etc.)
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isSurrogate(c)) {
                    return scalarFalse.getList();
                }
            }
        }

        return RuntimeScalarCache.scalarTrue.getList();
    }

    /**
     * find_encoding($encoding)
     * Returns a blessed Encode::Encoding object for the given encoding name.
     * First checks %Encode::Encoding for Perl-registered encodings,
     * then falls back to Java Charset lookup.
     */
    public static RuntimeList find_encoding(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("Bad number of arguments for find_encoding");
        }

        String encodingName = args.get(0).toString();

        // Check %Encode::Encoding for Perl-registered encodings first
        RuntimeScalar perlEncObj = lookupPerlEncoding(encodingName);
        if (perlEncObj != null) {
            return perlEncObj.getList();
        }

        try {
            Charset charset = getCharset(encodingName);
            // Create a blessed hash with the charset name
            RuntimeHash encObj = new RuntimeHash();
            encObj.put("Name", new RuntimeScalar(charset.name()));
            RuntimeScalar ref = encObj.createReference();
            ReferenceOperators.bless(ref, new RuntimeScalar("Encode::Encoding"));
            return ref.getList();
        } catch (Exception e) {
            // Return undef if encoding not found
            return scalarUndef.getList();
        }
    }

    /**
     * find_mime_encoding($mime_name)
     * Looks up an encoding by its MIME name. Delegates to find_encoding
     * after checking %Encode::MIME_Name or similar mapping.
     * In practice, most MIME names match charset names directly.
     */
    public static RuntimeList find_mime_encoding(RuntimeArray args, int ctx) {
        // Delegate to find_encoding — it checks %Encode::Encoding first,
        // then Java charsets, which covers MIME names like "UTF-8", "ISO-8859-1"
        return find_encoding(args, ctx);
    }

    /**
     * Encode::Encoding->encode($string [, $check])
     * Encodes a string to octets using this encoding.
     */
    public static RuntimeList encoding_encode(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("Bad number of arguments for Encode::Encoding::encode");
        }

        // Return undef if input string is undef
        if (!args.get(1).getDefinedBoolean()) {
            return scalarUndef.getList();
        }

        RuntimeScalar self = args.get(0);
        String string = args.get(1).toString();

        // Extract charset name from the blessed hash
        RuntimeHash hash = (RuntimeHash) self.value;
        String charsetName = hash.get("Name").toString();

        int check = parseCheck(args, 2);
        RuntimeScalar codeRef = getCheckCodeRef(args, 2);

        try {
            Charset charset = getCharset(charsetName);

            if (check == FB_DEFAULT_VAL) {
                // Fast path: no error handling
                byte[] bytes = string.getBytes(charset);
                return new RuntimeScalar(bytes).getList();
            }

            // Slow path with error handling
            return encodeWithCharset(string, charset, charsetName, check, codeRef, args, 1).getList();
        } catch (Exception e) {
            throw new RuntimeException("Cannot encode string with " + charsetName + ": " + e.getMessage());
        }
    }

    /**
     * Encode::Encoding->decode($octets [, $check])
     * Decodes octets to a string using this encoding.
     */
    public static RuntimeList encoding_decode(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("Bad number of arguments for Encode::Encoding::decode");
        }

        // Return undef if input octets is undef
        if (!args.get(1).getDefinedBoolean()) {
            return scalarUndef.getList();
        }

        RuntimeScalar self = args.get(0);
        String octets = args.get(1).toString();

        // Extract charset name from the blessed hash
        RuntimeHash hash = (RuntimeHash) self.value;
        String charsetName = hash.get("Name").toString();

        int check = parseCheck(args, 2);
        RuntimeScalar codeRef = getCheckCodeRef(args, 2);

        try {
            Charset charset = getCharset(charsetName);

            // Check for wide characters (code points > 255) in input.
            // These cannot be valid octets and indicate invalid input.
            if ((check & 0x01) != 0) { // DIE_ON_ERR
                for (int i = 0; i < octets.length(); i++) {
                    if (octets.charAt(i) > 255) {
                        throw new PerlCompilerException(
                                "Cannot decode string with wide characters at " +
                                        "Encode.pm line 0.");
                    }
                }
            }

            byte[] bytes = octets.getBytes(StandardCharsets.ISO_8859_1);
            bytes = trimOrphanBytes(bytes, charset);

            if (check == FB_DEFAULT_VAL) {
                // Fast path: no error handling
                String decoded = new String(bytes, charset);
                return new RuntimeScalar(decoded).getList();
            }

            // Slow path with error handling
            return decodeWithCharset(bytes, charset, charsetName, check, codeRef, args, 1).getList();
        } catch (Exception e) {
            if ((check & 0x01) != 0) {
                throw new PerlCompilerException(charsetName + " \"\\x{" +
                        String.format("%02X", (int) octets.charAt(0)) +
                        "}\" does not map to Unicode");
            }
            // Default: silently return best effort
            byte[] bytes = octets.getBytes(StandardCharsets.ISO_8859_1);
            String decoded = new String(bytes, getCharset(charsetName));
            return new RuntimeScalar(decoded).getList();
        }
    }

    /**
     * Encode::Encoding->name()
     * Returns the canonical name of this encoding.
     */
    public static RuntimeList encoding_name(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("Bad number of arguments for Encode::Encoding::name");
        }

        RuntimeScalar self = args.get(0);
        RuntimeHash hash = (RuntimeHash) self.value;
        return hash.get("Name").getList();
    }

    /**
     * from_to($octets, $from_enc, $to_enc [, $check])
     * Converts in-place the octet sequence from one encoding to another.
     */
    public static RuntimeList from_to(RuntimeArray args, int ctx) {
        if (args.size() < 3) {
            throw new IllegalStateException("Bad number of arguments for from_to");
        }

        RuntimeScalar octetsRef = args.get(0);
        String fromEnc = args.get(1).toString();
        String toEnc = args.get(2).toString();
        int check = parseCheck(args, 3);
        RuntimeScalar codeRef = getCheckCodeRef(args, 3);

        try {
            Charset fromCharset = getCharset(fromEnc);
            Charset toCharset = getCharset(toEnc);

            // Get the octets
            String octets = octetsRef.toString();
            byte[] bytes = octets.getBytes(StandardCharsets.ISO_8859_1);

            // Decode from source encoding
            bytes = trimOrphanBytes(bytes, fromCharset);

            if (check == FB_DEFAULT_VAL) {
                // Fast path
                String decoded = new String(bytes, fromCharset);
                byte[] encoded = decoded.getBytes(toCharset);
                octetsRef.set(new String(encoded, StandardCharsets.ISO_8859_1));
                octetsRef.type = BYTE_STRING;
                return new RuntimeScalar(decoded.length()).getList();
            }

            // Slow path: decode with error handling using shared helper
            RuntimeScalar decodedScalar = decodeWithCharset(bytes, fromCharset, fromEnc, check, codeRef, null, -1);
            String decoded = decodedScalar.toString();

            // Then encode to target with error handling
            RuntimeScalar encodedScalar = encodeWithCharset(decoded, toCharset, toEnc, check, codeRef, null, -1);

            // Update the original scalar in-place
            octetsRef.set(encodedScalar.value);
            octetsRef.type = BYTE_STRING;

            // Return the number of characters converted
            return new RuntimeScalar(decoded.length()).getList();
        } catch (Exception e) {
            throw new RuntimeException("Cannot convert from " + fromEnc + " to " + toEnc + ": " + e.getMessage());
        }
    }

    /**
     * _utf8_on($string)
     * Turns on the UTF-8 flag on the string. Returns the previous state of the flag.
     */
    public static RuntimeList _utf8_on(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("Bad number of arguments for _utf8_on");
        }
        RuntimeScalar arg = args.get(0);
        boolean wasUtf8 = (arg.type == STRING);
        if (arg.type == BYTE_STRING) {
            // Re-decode the byte string as UTF-8 to get proper characters
            // e.g., bytes \xC3\xA9 -> character U+00E9 (é)
            String s = arg.toString();
            byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
            arg.set(new String(bytes, StandardCharsets.UTF_8));
        }
        // Set the UTF-8 flag (change type to STRING)
        arg.type = STRING;
        return new RuntimeScalar(wasUtf8).getList();
    }

    /**
     * _utf8_off($string)
     * Turns off the UTF-8 flag on the string. Returns the previous state of the flag.
     */
    public static RuntimeList _utf8_off(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("Bad number of arguments for _utf8_off");
        }
        RuntimeScalar arg = args.get(0);
        boolean wasUtf8 = (arg.type == STRING);
        if (wasUtf8) {
            String s = arg.toString();
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            arg.set(new String(bytes, StandardCharsets.ISO_8859_1));
        }
        arg.type = BYTE_STRING;
        return new RuntimeScalar(wasUtf8).getList();
    }

    /**
     * Looks up a Perl-registered encoding object in %Encode::Encoding.
     * Returns the encoding object (blessed hashref) or null if not found.
     */
    private static RuntimeScalar lookupPerlEncoding(String encodingName) {
        RuntimeHash encodingHash = GlobalVariable.getGlobalHash("Encode::Encoding");
        if (encodingHash != null) {
            RuntimeScalar encObj = encodingHash.get(encodingName);
            if (encObj != null && encObj.getDefinedBoolean()) {
                return encObj;
            }
        }
        return null;
    }

    /**
     * Dispatches an encode or decode call to a Perl encoding object's method.
     * Calls $encObj->encode($string, $check) or $encObj->decode($octets, $check).
     */
    private static RuntimeList dispatchToEncodingObject(RuntimeScalar encObj, String method,
                                                         RuntimeArray origArgs, int stringArgIndex,
                                                         int checkArgIndex, int ctx) {
        RuntimeArray callArgs = new RuntimeArray();
        callArgs.push(origArgs.get(stringArgIndex));
        if (origArgs.size() > checkArgIndex) {
            callArgs.push(origArgs.get(checkArgIndex));
        }
        return RuntimeCode.call(
                encObj,
                new RuntimeScalar(method),
                null,
                callArgs,
                ctx
        );
    }

    /**
     * Trims orphan trailing bytes for fixed-width encodings.
     * Perl's Encode silently drops incomplete trailing code units
     * (e.g., an odd byte at the end of UTF-16 input).
     * Java's String(byte[], Charset) replaces them with U+FFFD instead.
     */
    private static byte[] trimOrphanBytes(byte[] bytes, Charset charset) {
        String name = charset.name().toLowerCase();
        int codeUnitSize = 0;
        if (name.contains("utf-16") || name.contains("utf16") || name.contains("ucs-2") || name.contains("ucs2")) {
            codeUnitSize = 2;
        } else if (name.contains("utf-32") || name.contains("utf32")) {
            codeUnitSize = 4;
        }
        if (codeUnitSize > 1) {
            int remainder = bytes.length % codeUnitSize;
            if (remainder != 0) {
                bytes = java.util.Arrays.copyOf(bytes, bytes.length - remainder);
            }
        }
        return bytes;
    }

    /**
     * Helper method to get a Charset from an encoding name.
     * Handles common aliases and Perl-style encoding names.
     */
    private static Charset getCharset(String encodingName) {
        // Check aliases first
        Charset charset = CHARSET_ALIASES.get(encodingName);
        if (charset != null) {
            return charset;
        }

        // Try to get charset by name
        try {
            return Charset.forName(encodingName);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new RuntimeException("Unknown encoding: " + encodingName);
        }
    }
}
