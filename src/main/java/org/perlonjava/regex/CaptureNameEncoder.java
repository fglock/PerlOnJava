package org.perlonjava.regex;

import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

/**
 * Utility class for encoding/decoding capture group names to work around Java regex limitations.
 *
 * <h2>Purpose</h2>
 * Java's regex engine has several limitations compared to Perl:
 * <ul>
 *   <li>No support for executing code in regex ((?{...}) blocks)</li>
 *   <li>Named captures cannot contain underscores</li>
 *   <li>Duplicate capture group names not supported</li>
 * </ul>
 *
 * <h2>Solution: In-Place Encoding</h2>
 * We encode information directly in the capture group name, then decode it at runtime.
 *
 * <h2>Encoding Formats</h2>
 *
 * <h3>Code Block Constants (?{...})</h3>
 * <pre>
 * (?{42})      → (?&lt;cb000n42&gt;)      // number: cb + counter + 'n' + value
 * (?{-3.14})   → (?&lt;cb001nm3p14&gt;)   // negative decimal: 'm'=minus, 'p'=point
 * (?{'hello'}) → (?&lt;cb002s00680065006c006c006f&gt;) // string: hex encoded (4 digits per char)
 * (?{undef})   → (?&lt;cb003u&gt;)         // undef: 'u' type
 * </pre>
 *
 * <h3>Underscore in Names (Future)</h3>
 * <pre>
 * (?&lt;my_name&gt;) → (?&lt;myU95name&gt;)    // U95 = underscore (ASCII 95)
 * </pre>
 *
 * <h3>Duplicate Names (Future)</h3>
 * <pre>
 * (?&lt;name&gt;a)|(?&lt;name&gt;b) → (?&lt;nameD1&gt;a)|(?&lt;nameD2&gt;b) // D1, D2 = duplicate markers
 * </pre>
 *
 * <h2>Decoding</h2>
 * When user accesses captures:
 * <ul>
 *   <li>%CAPTURE filters and decodes names back to original</li>
 *   <li>$^R extracts value from cb* capture names</li>
 *   <li>Internal captures (cb*) hidden from user</li>
 * </ul>
 *
 * <h2>Architecture Benefits</h2>
 * <ul>
 *   <li>Self-contained: pattern carries its own metadata</li>
 *   <li>No static state accumulation</li>
 *   <li>Works with regex caching</li>
 *   <li>Reusable pattern for multiple Java limitations</li>
 * </ul>
 */
public class CaptureNameEncoder {

    /**
     * Maximum length for a Java capture group name.
     * Longer names may cause pattern compilation issues.
     */
    public static final int MAX_CAPTURE_NAME_LENGTH = 200;

    /**
     * Encodes a code block constant value into a capture group name.
     * Simple approach: hex-encode the string representation.
     *
     * @param counter Unique counter for this code block
     * @param value   The constant value (any type)
     * @return Encoded capture group name, or null if encoding fails
     */
    public static String encodeCodeBlockValue(int counter, RuntimeScalar value) {
        try {
            // Convert value to string
            String valueStr = value.toString();

            // Determine type: 'n' for numbers, 's' for strings
            char typeIndicator;
            if (value.type == RuntimeScalarType.INTEGER || value.type == RuntimeScalarType.DOUBLE) {
                typeIndicator = 'n';
            } else {
                typeIndicator = 's';
            }

            // Hex encode the string (4 hex digits per character for Unicode safety)
            StringBuilder hexValue = new StringBuilder();
            for (char c : valueStr.toCharArray()) {
                hexValue.append(String.format("%04x", (int) c));
            }

            // Format: cb000nHEX or cb000sHEX where 000 is counter, n/s is type, HEX is encoded value
            String captureName = String.format("cb%03d%c%s", counter, typeIndicator, hexValue);

            // Check length limit
            if (captureName.length() > MAX_CAPTURE_NAME_LENGTH) {
                return null; // Too long, caller should use fallback
            }

            return captureName;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Decodes a code block constant value from a capture group name.
     * Format: cb000nHEX (number), cb000sHEX (string), or cb000u (undef)
     *
     * @param captureName The encoded capture group name (e.g., "cb0000034003200")
     * @return The decoded constant value, or null if decoding fails
     */
    public static RuntimeScalar decodeCodeBlockValue(String captureName) {
        try {
            // Format: cb000nHEX, cb000sHEX, or cb000u where cb=prefix, 000=counter, n/s/u=type, HEX=value
            if (captureName == null || !captureName.startsWith("cb")) {
                return null;
            }

            // Minimum length is 6 (cb + 3-digit counter + type indicator)
            if (captureName.length() < 6) {
                return null;
            }

            // Extract type indicator at position 5
            char typeIndicator = captureName.charAt(5);

            // Handle undef type
            if (typeIndicator == 'u') {
                return org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;
            }

            // Skip "cb" prefix, 3-digit counter, and type indicator to get hex value
            String hexValue = captureName.substring(6);

            // Decode from hex (4 hex digits per character)
            StringBuilder decoded = new StringBuilder();
            for (int i = 0; i < hexValue.length(); i += 4) {
                if (i + 4 <= hexValue.length()) {
                    String hexChar = hexValue.substring(i, i + 4);
                    int charCode = Integer.parseInt(hexChar, 16);
                    decoded.append((char) charCode);
                }
            }

            return new RuntimeScalar(decoded.toString());
        } catch (Exception e) {
            // Decoding failed
        }
        return null;
    }

    /**
     * Checks if a capture group name is an internal code block capture.
     * These should be filtered from user-visible variables like %CAPTURE.
     *
     * @param captureName The capture group name to check
     * @return true if this is an internal code block capture
     */
    public static boolean isCodeBlockCapture(String captureName) {
        return captureName != null && captureName.startsWith("cb") && captureName.length() > 5;
    }

    // FUTURE ENHANCEMENTS:
    // 
    // For underscore support: (?<my_name>)
    // Use the same hex encoding pattern: (?<ncHEX>) where HEX encodes "my_name"
    // Then %CAPTURE decodes back to show original name to user
    //
    // For duplicate names: (?<name>a)|(?<name>b)  
    // Encode with disambiguation: (?<ncHEX1>a)|(?<ncHEX2>b) where HEX encodes "name"
    // Track mapping for proper capture group retrieval
    //
    // The generic hex encoding pattern is reusable for all Java regex limitations!
}
