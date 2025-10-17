package org.perlonjava.util;

/**
 * Custom Base64 encoder/decoder implementation that matches Perl's MIME::Base64 behavior.
 * This is a simple implementation that doesn't use java.util.Base64.
 */
public class Base64Util {
    private static final String BASE64_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final char PAD = '=';
    private static final int MASK_6BITS = 0x3f;
    private static final int MASK_8BITS = 0xff;
    private static final int MASK_16BITS = 0xffff;

    // Lookup table for decoding
    private static final int[] DECODE_TABLE = new int[256];
    
    static {
        // Initialize decode table
        for (int i = 0; i < 256; i++) {
            DECODE_TABLE[i] = -1;
        }
        
        // Fill valid characters
        for (int i = 0; i < BASE64_CHARS.length(); i++) {
            DECODE_TABLE[BASE64_CHARS.charAt(i)] = i;
        }
    }

    /**
     * Encodes binary data into Base64 string without line breaks.
     */
    public static String encode(byte[] data) {
        if (data == null) {
            return "";
        }
        
        int len = data.length;
        if (len == 0) {
            return "";
        }
        
        StringBuilder result = new StringBuilder();
        
        // Process 3 bytes at a time
        int i = 0;
        for (; i < len - 2; i += 3) {
            int byte1 = data[i] & MASK_8BITS;
            int byte2 = data[i + 1] & MASK_8BITS;
            int byte3 = data[i + 2] & MASK_8BITS;
            
            // Get 4 6-bit values
            int c1 = byte1 >>> 2;
            int c2 = ((byte1 & 0x03) << 4) | (byte2 >>> 4);
            int c3 = ((byte2 & 0x0f) << 2) | (byte3 >>> 6);
            int c4 = byte3 & MASK_6BITS;
            
            // Convert to Base64 characters
            result.append(BASE64_CHARS.charAt(c1));
            result.append(BASE64_CHARS.charAt(c2));
            result.append(BASE64_CHARS.charAt(c3));
            result.append(BASE64_CHARS.charAt(c4));
        }
        
        // Handle padding for remaining bytes
        if (i < len) {
            int byte1 = data[i] & MASK_8BITS;
            
            if (i + 1 < len) {
                // 2 bytes remaining
                int byte2 = data[i + 1] & MASK_8BITS;
                
                int c1 = byte1 >>> 2;
                int c2 = ((byte1 & 0x03) << 4) | (byte2 >>> 4);
                int c3 = (byte2 & 0x0f) << 2;
                
                result.append(BASE64_CHARS.charAt(c1));
                result.append(BASE64_CHARS.charAt(c2));
                result.append(BASE64_CHARS.charAt(c3));
                result.append(PAD); // One padding character
            } else {
                // 1 byte remaining
                int c1 = byte1 >>> 2;
                int c2 = (byte1 & 0x03) << 4;
                
                result.append(BASE64_CHARS.charAt(c1));
                result.append(BASE64_CHARS.charAt(c2));
                result.append(PAD).append(PAD); // Two padding characters
            }
        }
        
        return result.toString();
    }
    
    /**
     * Decodes a Base64 string into binary data.
     * Follows Perl's MIME::Base64 behavior of ignoring invalid characters.
     */
    public static byte[] decode(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return new byte[0];
        }
        
        // Filter out non-Base64 characters (like Perl's MIME::Base64)
        StringBuilder filtered = new StringBuilder();
        for (int i = 0; i < base64.length(); i++) {
            char c = base64.charAt(i);
            if (c == '=' || (c < 128 && DECODE_TABLE[c] != -1)) {
                filtered.append(c);
            }
        }
        
        String cleanBase64 = filtered.toString();
        if (cleanBase64.isEmpty()) {
            return new byte[0];
        }
        
        // Find the first padding character and truncate after it
        int padIndex = cleanBase64.indexOf(PAD);
        if (padIndex >= 0) {
            cleanBase64 = cleanBase64.substring(0, padIndex);
        }
        
        // Calculate output length (3 bytes for every 4 Base64 chars, rounded down)
        int len = cleanBase64.length();
        int outputLen = (len * 3) / 4;
        byte[] result = new byte[outputLen];
        
        int resultIndex = 0;
        int i = 0;
        
        // Process 4 characters at a time
        for (; i <= len - 4; i += 4) {
            int c1 = getValue(cleanBase64.charAt(i));
            int c2 = getValue(cleanBase64.charAt(i + 1));
            int c3 = getValue(cleanBase64.charAt(i + 2));
            int c4 = getValue(cleanBase64.charAt(i + 3));
            
            // Combine 4 6-bit values into 3 bytes
            result[resultIndex++] = (byte) ((c1 << 2) | (c2 >>> 4));
            result[resultIndex++] = (byte) ((c2 << 4) | (c3 >>> 2));
            result[resultIndex++] = (byte) ((c3 << 6) | c4);
        }
        
        // Handle remaining characters (shouldn't happen with proper padding)
        if (i < len) {
            // We need at least 2 characters to produce a byte
            if (i + 1 < len) {
                int c1 = getValue(cleanBase64.charAt(i));
                int c2 = getValue(cleanBase64.charAt(i + 1));
                
                // First byte: 6 bits from c1 and 2 bits from c2
                if (resultIndex < result.length) {
                    result[resultIndex++] = (byte) ((c1 << 2) | (c2 >>> 4));
                }
                
                // If we have a third character, we can get another byte
                if (i + 2 < len) {
                    int c3 = getValue(cleanBase64.charAt(i + 2));
                    if (resultIndex < result.length) {
                        result[resultIndex++] = (byte) ((c2 << 4) | (c3 >>> 2));
                    }
                }
                // If we have exactly 3 characters, we can get a second byte
                else if (i + 2 == len) {
                    if (resultIndex < result.length) {
                        result[resultIndex++] = (byte) (c2 << 4);
                    }
                }
            }
        }
        
        return result;
    }
    
    private static int getValue(char c) {
        return (c < 128) ? DECODE_TABLE[c] : -1;
    }
    
    /**
     * Encodes data with MIME line breaks (76 chars per line).
     */
    public static String encodeMime(byte[] data, String lineSeparator) {
        String base64 = encode(data);
        if (lineSeparator == null || lineSeparator.isEmpty()) {
            return base64;
        }
        
        StringBuilder result = new StringBuilder();
        int pos = 0;
        int len = base64.length();
        
        while (pos < len) {
            int end = Math.min(pos + 76, len);
            if (pos > 0) {
                result.append(lineSeparator);
            }
            result.append(base64, pos, end);
            pos = end;
        }
        
        // Always add line separator at the end if not empty
        if (len > 0) {
            result.append(lineSeparator);
        }
        
        return result.toString();
    }
}
