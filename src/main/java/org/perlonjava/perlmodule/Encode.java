package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarCache;
import org.perlonjava.runtime.RuntimeHash;
import org.perlonjava.runtime.RuntimeScalarType;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.operators.ModuleOperators;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.HashMap;
import java.util.Map;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.RuntimeScalarType.BYTE_STRING;

/**
 * The Encode module for PerlOnJava.
 * Provides character encoding/decoding functionality similar to Perl's Encode module.
 */
public class Encode extends PerlModuleBase {

    private static final Map<String, Charset> CHARSET_ALIASES = new HashMap<>();

    static {
        // Initialize common charset aliases
        CHARSET_ALIASES.put("utf8", StandardCharsets.UTF_8);
        CHARSET_ALIASES.put("UTF8", StandardCharsets.UTF_8);
        CHARSET_ALIASES.put("utf-8", StandardCharsets.UTF_8);
        CHARSET_ALIASES.put("UTF-8", StandardCharsets.UTF_8);

        CHARSET_ALIASES.put("latin1", StandardCharsets.ISO_8859_1);
        CHARSET_ALIASES.put("Latin1", StandardCharsets.ISO_8859_1);
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
    }

    public Encode() {
        super("Encode", false);  // Don't set %INC, let Encode.pm do that
    }

    public static void initialize() {
        Encode encode = new Encode();
        encode.initializeExporter();
        encode.defineExport("EXPORT", "encode", "decode", "encode_utf8", "decode_utf8",
                "is_utf8", "find_encoding", "from_to", "resolve_alias", "encodings");
        try {
            encode.registerMethod("encode", null);
            encode.registerMethod("decode", null);
            encode.registerMethod("encode_utf8", null);
            encode.registerMethod("decode_utf8", null);
            encode.registerMethod("is_utf8", null);
            encode.registerMethod("find_encoding", null);
            encode.registerMethod("from_to", null);
            encode.registerMethod("resolve_alias", null);
            encode.registerMethod("encodings", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Encode method: " + e.getMessage());
        }
    }

    /**
     * encode($encoding, $string [, $check])
     * Encodes a string from Perl's internal format to the specified encoding.
     */
    public static RuntimeList encode(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("Bad number of arguments for encode");
        }

        String encodingName = args.get(0).toString();
        String string = args.get(1).toString();
        // TODO: Handle $check parameter (args.get(2)) for error handling modes

        try {
            Charset charset = getCharset(encodingName);
            byte[] bytes = string.getBytes(charset);

            // Return the encoded bytes as a byte string, inside a list
            return new RuntimeScalar(bytes).getList();
        } catch (Exception e) {
            throw new RuntimeException("Cannot encode string to " + encodingName + ": " + e.getMessage());
        }
    }

    /**
     * decode($encoding, $octets [, $check])
     * Decodes a string from the specified encoding to Perl's internal format.
     */
    public static RuntimeList decode(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("Bad number of arguments for decode");
        }

        String encodingName = args.get(0).toString();
        String octets = args.get(1).toString();
        // TODO: Handle $check parameter (args.get(2)) for error handling modes

        try {
            Charset charset = getCharset(encodingName);
            // Convert the string to bytes assuming it contains raw octets
            byte[] bytes = octets.getBytes(StandardCharsets.ISO_8859_1);
            String decoded = new String(bytes, charset);

            return new RuntimeScalar(decoded).getList();
        } catch (Exception e) {
            throw new RuntimeException("Cannot decode string from " + encodingName + ": " + e.getMessage());
        }
    }

    /**
     * encode_utf8($string)
     * Equivalent to encode("utf8", $string)
     */
    public static RuntimeList encode_utf8(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("Bad number of arguments for encode_utf8");
        }

        String string = args.get(0).toString();
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);

        // Return the encoded bytes as a string, inside a list
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

        String octets = args.get(0).toString();
        // TODO: Handle $check parameter (args.get(1)) for error handling modes

        try {
            // Convert the string to bytes assuming it contains raw octets
            byte[] bytes = octets.getBytes(StandardCharsets.ISO_8859_1);
            String decoded = new String(bytes, StandardCharsets.UTF_8);

            return new RuntimeScalar(decoded).getList();
        } catch (Exception e) {
            throw new RuntimeException("Cannot decode UTF-8 string: " + e.getMessage());
        }
    }

    /**
     * is_utf8($string [, $check])
     * Tests whether the UTF8 flag is turned on in the string.
     */
    public static RuntimeList is_utf8(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("Bad number of arguments for is_utf8");
        }

        return RuntimeScalarCache.getScalarBoolean(args.get(0).type == BYTE_STRING).getList();

//        // In PerlOnJava, strings are always internally Unicode (Java strings)
//        // So we'll check if the string contains any non-ASCII characters
//        String string = args.get(0).toString();
//        boolean hasNonAscii = false;
//
//        for (int i = 0; i < string.length(); i++) {
//            if (string.charAt(i) > 127) {
//                hasNonAscii = true;
//                break;
//            }
//        }
//
//        return new RuntimeScalar(hasNonAscii).getList();
    }

    /**
     * find_encoding($encoding)
     * Returns an encoding object for the given encoding name.
     */
    public static RuntimeList find_encoding(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("Bad number of arguments for find_encoding");
        }

        String encodingName = args.get(0).toString();

        try {
            Charset charset = getCharset(encodingName);
            
            // Create an encoding object - just a blessed hashref with the charset name
            // The Encode::XS package provides the decode/encode methods
            RuntimeHash encodingObj = new RuntimeHash();
            encodingObj.put("Name", new RuntimeScalar(charset.name()));
            encodingObj.put("name", new RuntimeScalar(charset.name()));
            
            // Bless the object into Encode::XS package
            encodingObj.setBlessId(NameNormalizer.getBlessId("Encode::XS"));
            
            // Create a reference to the hash
            RuntimeScalar blessed = new RuntimeScalar(encodingObj);
            blessed.type = RuntimeScalarType.HASHREFERENCE;
            
            return blessed.getList();
        } catch (Exception e) {
            // Return undef if encoding not found
            return scalarUndef.getList();
        }
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
        // TODO: Handle $check parameter (args.get(3)) for error handling modes

        try {
            Charset fromCharset = getCharset(fromEnc);
            Charset toCharset = getCharset(toEnc);

            // Get the octets
            String octets = octetsRef.toString();
            byte[] bytes = octets.getBytes(StandardCharsets.ISO_8859_1);

            // Decode from source encoding
            String decoded = new String(bytes, fromCharset);

            // Encode to target encoding
            byte[] encoded = decoded.getBytes(toCharset);

            // Update the original scalar in-place
            octetsRef.set(new String(encoded, StandardCharsets.ISO_8859_1));

            // Return the number of characters converted
            return new RuntimeScalar(decoded.length()).getList();
        } catch (Exception e) {
            throw new RuntimeException("Cannot convert from " + fromEnc + " to " + toEnc + ": " + e.getMessage());
        }
    }

    /**
     * resolve_alias($encoding_name)
     * Resolves an encoding alias to its canonical name.
     * Returns the canonical encoding name if found, undef otherwise.
     */
    public static RuntimeList resolve_alias(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        String encodingName = args.get(0).toString();

        // Check if it's an alias we know
        Charset charset = CHARSET_ALIASES.get(encodingName);
        if (charset != null) {
            return new RuntimeScalar(charset.name()).getList();
        }

        // Try to get charset by name and return its canonical name
        try {
            charset = Charset.forName(encodingName);
            return new RuntimeScalar(charset.name()).getList();
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            // Return undef if encoding not found
            return scalarUndef.getList();
        }
    }

    /**
     * encodings([$type])
     * Returns a list of all available encoding names.
     * Optional $type parameter can be ":all" to include all encodings.
     */
    public static RuntimeList encodings(RuntimeArray args, int ctx) {
        RuntimeArray result = new RuntimeArray();
        
        // Add all our known aliases
        for (String alias : CHARSET_ALIASES.keySet()) {
            result.push(new RuntimeScalar(alias));
        }
        
        // Add all available charsets from Java
        for (Charset charset : Charset.availableCharsets().values()) {
            result.push(new RuntimeScalar(charset.name()));
        }
        
        return result.getList();
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