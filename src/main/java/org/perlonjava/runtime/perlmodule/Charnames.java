package org.perlonjava.runtime.perlmodule;

import com.ibm.icu.lang.UCharacter;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.HashMap;
import java.util.Map;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * Java-side implementation for _charnames module.
 * Provides Unicode character name lookup via ICU4J.
 */
public class Charnames extends PerlModuleBase {

    // Control character names that UCharacter.getName() doesn't return.
    // These are the "best" names as defined by Perl's charnames::viacode.
    private static final Map<Integer, String> CONTROL_CHAR_NAMES = new HashMap<>();

    static {
        // C0 control characters (U+0000-U+001F)
        CONTROL_CHAR_NAMES.put(0x00, "NULL");
        CONTROL_CHAR_NAMES.put(0x01, "START OF HEADING");
        CONTROL_CHAR_NAMES.put(0x02, "START OF TEXT");
        CONTROL_CHAR_NAMES.put(0x03, "END OF TEXT");
        CONTROL_CHAR_NAMES.put(0x04, "END OF TRANSMISSION");
        CONTROL_CHAR_NAMES.put(0x05, "ENQUIRY");
        CONTROL_CHAR_NAMES.put(0x06, "ACKNOWLEDGE");
        CONTROL_CHAR_NAMES.put(0x07, "ALERT");
        CONTROL_CHAR_NAMES.put(0x08, "BACKSPACE");
        CONTROL_CHAR_NAMES.put(0x09, "CHARACTER TABULATION");
        CONTROL_CHAR_NAMES.put(0x0A, "LINE FEED");
        CONTROL_CHAR_NAMES.put(0x0B, "LINE TABULATION");
        CONTROL_CHAR_NAMES.put(0x0C, "FORM FEED");
        CONTROL_CHAR_NAMES.put(0x0D, "CARRIAGE RETURN");
        CONTROL_CHAR_NAMES.put(0x0E, "SHIFT OUT");
        CONTROL_CHAR_NAMES.put(0x0F, "SHIFT IN");
        CONTROL_CHAR_NAMES.put(0x10, "DATA LINK ESCAPE");
        CONTROL_CHAR_NAMES.put(0x11, "DEVICE CONTROL ONE");
        CONTROL_CHAR_NAMES.put(0x12, "DEVICE CONTROL TWO");
        CONTROL_CHAR_NAMES.put(0x13, "DEVICE CONTROL THREE");
        CONTROL_CHAR_NAMES.put(0x14, "DEVICE CONTROL FOUR");
        CONTROL_CHAR_NAMES.put(0x15, "NEGATIVE ACKNOWLEDGE");
        CONTROL_CHAR_NAMES.put(0x16, "SYNCHRONOUS IDLE");
        CONTROL_CHAR_NAMES.put(0x17, "END OF TRANSMISSION BLOCK");
        CONTROL_CHAR_NAMES.put(0x18, "CANCEL");
        CONTROL_CHAR_NAMES.put(0x19, "END OF MEDIUM");
        CONTROL_CHAR_NAMES.put(0x1A, "SUBSTITUTE");
        CONTROL_CHAR_NAMES.put(0x1B, "ESCAPE");
        CONTROL_CHAR_NAMES.put(0x1C, "INFORMATION SEPARATOR FOUR");
        CONTROL_CHAR_NAMES.put(0x1D, "INFORMATION SEPARATOR THREE");
        CONTROL_CHAR_NAMES.put(0x1E, "INFORMATION SEPARATOR TWO");
        CONTROL_CHAR_NAMES.put(0x1F, "INFORMATION SEPARATOR ONE");
        // U+007F
        CONTROL_CHAR_NAMES.put(0x7F, "DELETE");
        // C1 control characters (U+0080-U+009F)
        CONTROL_CHAR_NAMES.put(0x80, "PADDING CHARACTER");
        CONTROL_CHAR_NAMES.put(0x81, "HIGH OCTET PRESET");
        CONTROL_CHAR_NAMES.put(0x82, "BREAK PERMITTED HERE");
        CONTROL_CHAR_NAMES.put(0x83, "NO BREAK HERE");
        CONTROL_CHAR_NAMES.put(0x84, "INDEX");
        CONTROL_CHAR_NAMES.put(0x85, "NEXT LINE");
        CONTROL_CHAR_NAMES.put(0x86, "START OF SELECTED AREA");
        CONTROL_CHAR_NAMES.put(0x87, "END OF SELECTED AREA");
        CONTROL_CHAR_NAMES.put(0x88, "CHARACTER TABULATION SET");
        CONTROL_CHAR_NAMES.put(0x89, "CHARACTER TABULATION WITH JUSTIFICATION");
        CONTROL_CHAR_NAMES.put(0x8A, "LINE TABULATION SET");
        CONTROL_CHAR_NAMES.put(0x8B, "PARTIAL LINE FORWARD");
        CONTROL_CHAR_NAMES.put(0x8C, "PARTIAL LINE BACKWARD");
        CONTROL_CHAR_NAMES.put(0x8D, "REVERSE LINE FEED");
        CONTROL_CHAR_NAMES.put(0x8E, "SINGLE SHIFT TWO");
        CONTROL_CHAR_NAMES.put(0x8F, "SINGLE SHIFT THREE");
        CONTROL_CHAR_NAMES.put(0x90, "DEVICE CONTROL STRING");
        CONTROL_CHAR_NAMES.put(0x91, "PRIVATE USE ONE");
        CONTROL_CHAR_NAMES.put(0x92, "PRIVATE USE TWO");
        CONTROL_CHAR_NAMES.put(0x93, "SET TRANSMIT STATE");
        CONTROL_CHAR_NAMES.put(0x94, "CANCEL CHARACTER");
        CONTROL_CHAR_NAMES.put(0x95, "MESSAGE WAITING");
        CONTROL_CHAR_NAMES.put(0x96, "START OF GUARDED AREA");
        CONTROL_CHAR_NAMES.put(0x97, "END OF GUARDED AREA");
        CONTROL_CHAR_NAMES.put(0x98, "START OF STRING");
        CONTROL_CHAR_NAMES.put(0x99, "SINGLE GRAPHIC CHARACTER INTRODUCER");
        CONTROL_CHAR_NAMES.put(0x9A, "SINGLE CHARACTER INTRODUCER");
        CONTROL_CHAR_NAMES.put(0x9B, "CONTROL SEQUENCE INTRODUCER");
        CONTROL_CHAR_NAMES.put(0x9C, "STRING TERMINATOR");
        CONTROL_CHAR_NAMES.put(0x9D, "OPERATING SYSTEM COMMAND");
        CONTROL_CHAR_NAMES.put(0x9E, "PRIVACY MESSAGE");
        CONTROL_CHAR_NAMES.put(0x9F, "APPLICATION PROGRAM COMMAND");
    }

    public Charnames() {
        super("_charnames", false);  // Don't set %INC - let the Perl _charnames.pm load normally
    }

    public static void initialize() {
        Charnames charnames = new Charnames();
        try {
            charnames.registerMethod("_java_viacode", "javaViacode", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing _charnames method: " + e.getMessage());
        }
    }

    /**
     * Returns the Unicode character name for a given code point.
     * Uses ICU4J's UCharacter.getName() which provides full Unicode name data,
     * with a fallback table for control characters that ICU4J doesn't name.
     *
     * @param args Code point as integer
     * @param ctx  Context
     * @return Character name string, or undef if not found
     */
    public static RuntimeList javaViacode(RuntimeArray args, int ctx) {
        int codePoint = args.getFirst().getInt();

        // Check control character names first (ICU4J returns null for these)
        String name = CONTROL_CHAR_NAMES.get(codePoint);
        if (name != null) {
            return new RuntimeList(new RuntimeScalar(name));
        }

        // Try ICU4J for all other characters
        name = UCharacter.getName(codePoint);
        if (name == null || name.isEmpty()) {
            return new RuntimeList(scalarUndef);
        }
        return new RuntimeList(new RuntimeScalar(name));
    }
}
