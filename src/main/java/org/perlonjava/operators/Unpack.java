package org.perlonjava.operators;

import org.perlonjava.operators.pack.PackHelper;
import org.perlonjava.operators.unpack.*;
import org.perlonjava.runtime.*;
import org.perlonjava.operators.FormatModifierValidator;

import java.math.BigInteger;
import java.util.*;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * Provides functionality to unpack binary data into a list of scalars
 * based on a specified template, similar to Perl's unpack function.
 */
public class Unpack {
    public static final Map<Character, FormatHandler> handlers = new HashMap<>();

    static {
        // Initialize format handlers
        handlers.put('c', new CFormatHandler(true));   // signed char
        handlers.put('C', new CFormatHandler(false));  // unsigned char
        handlers.put('S', new NumericFormatHandler.ShortHandler(false));
        handlers.put('s', new NumericFormatHandler.ShortHandler(true));
        handlers.put('L', new NumericFormatHandler.LongHandler(false));
        handlers.put('l', new NumericFormatHandler.LongHandler(true));
        handlers.put('i', new NumericFormatHandler.LongHandler(true));   // signed int
        handlers.put('I', new NumericFormatHandler.LongHandler(false));  // unsigned int
        handlers.put('N', new NumericFormatHandler.NetworkLongHandler());
        handlers.put('n', new NumericFormatHandler.NetworkShortHandler());
        handlers.put('V', new NumericFormatHandler.VAXLongHandler());
        handlers.put('v', new NumericFormatHandler.VAXShortHandler());
        handlers.put('q', new NumericFormatHandler.QuadHandler(true));   // signed 64-bit quad
        handlers.put('Q', new NumericFormatHandler.QuadHandler(false));  // unsigned 64-bit quad
        handlers.put('j', new NumericFormatHandler.QuadHandler(true));   // signed Perl IV
        handlers.put('J', new NumericFormatHandler.QuadHandler(false));  // unsigned Perl UV
        handlers.put('f', new NumericFormatHandler.FloatHandler());
        handlers.put('F', new NumericFormatHandler.DoubleHandler());  // F is double-precision like d
        handlers.put('d', new NumericFormatHandler.DoubleHandler());
        handlers.put('D', new NumericFormatHandler.DoubleHandler());  // D is long double, treat as double
        handlers.put('a', new StringFormatHandler('a'));
        handlers.put('A', new StringFormatHandler('A'));
        handlers.put('Z', new StringFormatHandler('Z'));
        handlers.put('b', new BitStringFormatHandler('b'));
        handlers.put('B', new BitStringFormatHandler('B'));
        handlers.put('h', new HexStringFormatHandler('h'));
        handlers.put('H', new HexStringFormatHandler('H'));
        handlers.put('W', new WFormatHandler());
        handlers.put('x', new XFormatHandler());
        handlers.put('X', new XBackwardHandler());
        handlers.put('w', new WBERFormatHandler());
        handlers.put('p', new PointerFormatHandler());
        handlers.put('P', new PointerFormatHandler());  // P uses same handler as p for simplicity
        handlers.put('u', new UuencodeFormatHandler());
        handlers.put('@', new AtFormatHandler());
        handlers.put('.', new DotFormatHandler());  // Add the dot handler
        // Note: U handler is created dynamically based on startsWithU
    }

    /**
     * unpack(template, data)
     *
     * @param ctx  call context
     * @param args argument list
     * @return list
     */
    public static RuntimeList unpack(int ctx, RuntimeBase... args) {
        RuntimeScalar templateScalar = (RuntimeScalar) args[0];
        RuntimeScalar packedData = args.length > 1 ? args[1].scalar() : scalarUndef;

        String template = templateScalar.toString();
        String dataString = packedData.toString();

        // Default mode is always C0 (character mode)
        boolean startsWithU = template.startsWith("U") && !template.startsWith("U0");

        // Create state object - always starts in character mode
        UnpackState state = new UnpackState(dataString, false);

        // Check if template starts with U0 to switch to byte mode
        if (template.startsWith("U0")) {
            state.switchToByteMode();
        }

        RuntimeList out = new RuntimeList();
        List<RuntimeBase> values = out.elements;

        // Stack to track mode for parentheses scoping
        Stack<Boolean> modeStack = new Stack<>();

        // Parse template
        int i = 0;
        while (i < template.length()) {
            char format = template.charAt(i);
            // DEBUG: unpack main loop i=" + i + ", format='" + format + "' (code " + (int) format + "), template='" + template + "', remaining='" + template.substring(i) + "'
            boolean isChecksum = false;
            int checksumBits = 16; // default

            // Check for checksum modifier
            if (format == '%') {
                isChecksum = true;
                i++;
                if (i >= template.length()) {
                    throw new PerlCompilerException("unpack: '%' must be followed by a format");
                }

                // Parse optional bit count
                if (Character.isDigit(template.charAt(i))) {
                    int j = i;
                    while (j < template.length() && Character.isDigit(template.charAt(j))) {
                        j++;
                    }
                    checksumBits = Integer.parseInt(template.substring(i, j));
                    i = j;
                    if (i >= template.length()) {
                        throw new PerlCompilerException("unpack: '%' must be followed by a format");
                    }
                }

                // Get the actual format after %
                format = template.charAt(i);
            }

            // Handle parentheses for grouping
            if (format == '(') {
                i = UnpackGroupProcessor.parseGroupSyntax(template, i, state, values, startsWithU, modeStack);
                i++;
                continue;
            }

            // Skip whitespace
            if (Character.isWhitespace(format)) {
                i++;
                continue;
            }

            // Skip comments
            if (format == '#') {
                i = UnpackParser.skipComment(template, i);
                i++;
                continue;
            }

            // Handle commas (skip with warning)
            if (format == ',') {
                // WARNING: Invalid type ',' in unpack
                i++;
                continue;
            }

            // Check if this numeric format is part of a '/' construct
            if (PackHelper.isNumericFormat(format) || format == 'Z' || format == 'A' || format == 'a') {
                int slashPos = PackHelper.checkForSlashConstruct(template, i);
                if (slashPos != -1) {
                    i = UnpackHelper.processSlashConstruct(template, i, format, state, values, startsWithU, modeStack);
                    i++; // Move past the last processed character
                    continue;
                }
            }

            // Check for standalone modifiers that should only appear after valid format characters
            if (format == '<' || format == '>') {
                // For unpack, we need to provide a generic format character since we don't have context
                // The tests expect "allowed only after types \S+ in unpack" format
                throw new PerlCompilerException("'" + format + "' allowed only after types " + 
                    FormatModifierValidator.getValidFormatsForModifier(FormatModifierValidator.Modifier.LITTLE_ENDIAN) + " in unpack");
            }
            if (format == '!') {
                throw new PerlCompilerException("'!' allowed only after types " + 
                    FormatModifierValidator.getValidFormatsForModifier(FormatModifierValidator.Modifier.NATIVE_SIZE) + " in unpack");
            }

            // Handle '/' for counted strings
            if (format == '/') {
                if (values.isEmpty()) {
                    throw new PerlCompilerException("'/' must follow a numeric type");
                }

                // Get the count from the last unpacked value
                RuntimeBase lastValue = values.getLast();
                int slashCount = ((RuntimeScalar) lastValue).getInt();
                values.removeLast(); // Remove the count value

                // Get the format that follows '/'
                i++;
                if (i >= template.length()) {
                    throw new PerlCompilerException("Code missing after '/'");
                }
                char stringFormat = template.charAt(i);

                // Check if it's a group
                if (stringFormat == '(') {
                    i = UnpackGroupProcessor.processSlashGroup(template, i, slashCount, state, values, startsWithU, modeStack);
                    continue;
                }

                // Original code for string formats
                if (stringFormat != 'a' && stringFormat != 'A' && stringFormat != 'Z') {
                    throw new PerlCompilerException("'/' must be followed by a string type");
                }

                // Parse optional count/star after string format
                boolean hasStarAfterSlash = false;
                if (i + 1 < template.length() && template.charAt(i + 1) == '*') {
                    hasStarAfterSlash = true;
                    i++; // Move to the '*'
                }

                // Unpack the string with the count from the previous numeric value
                FormatHandler stringHandler = handlers.get(stringFormat);
                stringHandler.unpack(state, values, slashCount, hasStarAfterSlash);

                // IMPORTANT: Skip past all characters we've processed
                // The continue statement will skip the normal i++ at the end of the loop
                i++;  // Move past the last character we processed
                continue;
            }

            // Check for explicit mode modifiers
            if (format == 'C' && i + 1 < template.length() && template.charAt(i + 1) == '0') {
                state.switchToCharacterMode();
                i += 2; // Skip 'C0'
                continue;
            } else if (format == 'U' && i + 1 < template.length() && template.charAt(i + 1) == '0') {
                state.switchToByteMode();
                i += 2; // Skip 'U0'
                continue;
            }

            // Parse count using UnpackParser
            UnpackParser.ParsedCount parsedCount = UnpackParser.parseRepeatCount(template, i);
            int count = parsedCount.count();
            boolean isStarCount = parsedCount.isStarCount();
            boolean hasShriek = parsedCount.hasShriek();
            boolean hasLittleEndian = parsedCount.hasLittleEndian();
            boolean hasBigEndian = parsedCount.hasBigEndian();
            i = parsedCount.endPosition();
            
            if (isStarCount) {
                count = UnpackHelper.getRemainingCount(state, format, startsWithU);
            }
            // Get handler and unpack
            FormatHandler handler = getHandler(format, startsWithU);
            if (handler != null) {
                // Set byte order based on endianness modifiers
                if (hasLittleEndian || hasBigEndian) {
                    state.setByteOrder(hasBigEndian);
                    // Ensure buffer is created with correct byte order
                    state.getBuffer();
                    state.setByteOrder(hasBigEndian);
                }
                
                if (format == '@') {
                    // DEBUG: Calling @ handler with count=" + count
                } else if (format == '.') {
                    // Special handling for '.' with '!' modifier
                    if (hasShriek) {
                        // .! means byte offset instead of character offset
                        handler = new DotShriekFormatHandler();
                    }
                } else if ((format == 'l' || format == 'L') && hasShriek) {
                    // Special handling for 'l!' and 'L!' - native size (8 bytes)
                    handler = new NativeLongFormatHandler(format == 'l');
                } else if ((format == 'n' || format == 'v' || format == 'N' || format == 'V') && hasShriek) {
                    // Special handling for network/VAX formats with '!' - makes them signed
                    if (format == 'n') {
                        handler = new NumericFormatHandler.NetworkShortHandler(true); // signed
                    } else if (format == 'v') {
                        handler = new NumericFormatHandler.VAXShortHandler(true); // signed
                    } else if (format == 'N') {
                        handler = new NumericFormatHandler.NetworkLongHandler(true); // signed
                    } else if (format == 'V') {
                        handler = new NumericFormatHandler.VAXLongHandler(true); // signed
                    }
                }
                // For 'p' format, create endianness-aware handler based on parsed modifiers
                if (format == 'p' && (hasLittleEndian || hasBigEndian)) {
                    // Create endianness-aware PointerFormatHandler
                    boolean bigEndian = hasBigEndian;
                    handler = new PointerFormatHandler(bigEndian);
                }

                if (isChecksum) {
                    // Special case: 'u' format checksums always return 0 in Perl
                    if (format == 'u') {
                        values.add(new RuntimeScalar(0));
                    } else {
                        // Handle checksum calculation - process ALL remaining data
                        List<RuntimeBase> tempValues = new ArrayList<>();
                    
                    // For checksums, we need to ensure we use the correct handler
                    // especially for native size formats like l! and L!
                    FormatHandler checksumHandler = handler;
                    if ((format == 'l' || format == 'L') && hasShriek) {
                        // Force use of NativeLongFormatHandler for checksum calculation
                        checksumHandler = new NativeLongFormatHandler(format == 'l');
                    }
                    
                    // For checksums, we need to consume ALL remaining data in the buffer
                    // The count and star flag are ignored - we process everything
                    int remainingBytes = state.remainingBytes();
                    
                    // Check if this format has native size modifier
                    boolean hasNativeSize = (format == 'l' || format == 'L') && hasShriek;
                    
                    int formatSize = getFormatSize(format, hasNativeSize);
                    
                    if (formatSize > 0) {
                        int remainingCount = remainingBytes / formatSize;
                        // Process all remaining values of this format using the correct handler
                        checksumHandler.unpack(state, tempValues, remainingCount, false);
                    } else {
                        // For variable-size formats, process what we can
                        checksumHandler.unpack(state, tempValues, count, isStarCount);
                    }

                    // Calculate checksum - use BigInteger for 64+ bit checksums to handle unsigned values
                    if (checksumBits >= 64) {
                        // Use BigInteger for 64+ bit checksums to handle unsigned values correctly
                        BigInteger bigChecksum = BigInteger.ZERO;
                        
                        if (format == 'b' || format == 'B') {
                            // For binary formats, count 1 bits
                            for (RuntimeBase value : tempValues) {
                                String binary = value.toString();
                                for (int j = 0; j < binary.length(); j++) {
                                    if (binary.charAt(j) == '1') {
                                        bigChecksum = bigChecksum.add(BigInteger.ONE);
                                    }
                                }
                            }
                        } else {
                            // For other formats, sum the numeric values
                            for (RuntimeBase value : tempValues) {
                                long val = ((RuntimeScalar) value).getLong();
                                // Handle negative values as unsigned
                                if (val < 0) {
                                    bigChecksum = bigChecksum.add(BigInteger.valueOf(val).add(BigInteger.ONE.shiftLeft(64)));
                                } else {
                                    bigChecksum = bigChecksum.add(BigInteger.valueOf(val));
                                }
                            }
                        }
                        
                        // Apply bit mask for 64-bit (not for 65+ as they overflow anyway)
                        if (checksumBits == 64) {
                            // 64-bit mask: 2^64 - 1 = 0xFFFFFFFFFFFFFFFF
                            BigInteger mask = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
                            bigChecksum = bigChecksum.and(mask);
                        }
                        // For checksumBits > 64, Perl treats it as no mask (full value)
                        
                        // Convert BigInteger to long - this will wrap around for unsigned values > Long.MAX_VALUE
                        // which matches Perl's behavior on 64-bit systems
                        values.add(new RuntimeScalar(bigChecksum.longValue()));
                    } else {
                        // Use long for checksums < 64 bits
                        long checksum = 0;
                        if (format == 'b' || format == 'B') {
                            // For binary formats, count 1 bits
                            for (RuntimeBase value : tempValues) {
                                String binary = value.toString();
                                for (int j = 0; j < binary.length(); j++) {
                                    if (binary.charAt(j) == '1') {
                                        checksum++;
                                    }
                                }
                            }
                        } else {
                            // For other formats, sum the numeric values
                            for (RuntimeBase value : tempValues) {
                                checksum += ((RuntimeScalar) value).getLong();
                            }
                        }

                        // Apply bit mask at the very end like Perl (not during accumulation)
                        if (checksumBits > 0 && checksumBits < 64) {
                            long mask = (1L << checksumBits) - 1;
                            checksum &= mask;
                        }

                        // Return the checksum value:
                        // - For checksums that fit in an int, return as int
                        // - For larger values or checksums with more than 32 bits, return as long
                        if (checksumBits > 32 || checksum > Integer.MAX_VALUE || checksum < Integer.MIN_VALUE) {
                            values.add(new RuntimeScalar(checksum));
                        } else {
                            values.add(new RuntimeScalar((int) checksum));
                        }
                    }
                    }
                } else {
                    handler.unpack(state, values, count, isStarCount);
                }
            } else {
                // Check for standalone * which is invalid
                if (format == '*') {
                    throw new PerlCompilerException("Invalid type '*' in unpack");
                }
                // Check for standalone modifiers that should only appear after valid format characters
                if (format == '<' || format == '>') {
                    throw new PerlCompilerException("'" + format + "' allowed only after types " + 
                        FormatModifierValidator.getValidFormatsForModifier(FormatModifierValidator.Modifier.LITTLE_ENDIAN) + " in unpack");
                }
                if (format == '!') {
                    throw new PerlCompilerException("'!' allowed only after types " + 
                        FormatModifierValidator.getValidFormatsForModifier(FormatModifierValidator.Modifier.NATIVE_SIZE) + " in unpack");
                }
                throw new PerlCompilerException("unpack: unsupported format character: " + format);
            }

            i++;
        }
        if (ctx == RuntimeContextType.SCALAR && !out.isEmpty()) {
            return out.elements.getFirst().getList();
        }
        return out;
    }

    public static FormatHandler getHandler(char format, boolean startsWithU) {
        if (format == 'U') {
            return new UFormatHandler(startsWithU);
        }
        return handlers.get(format);
    }

    /**
     * Get the byte size of a format character for checksum calculations
     * @param format The format character
     * @param hasNativeSize Whether the format has native size modifier (!)
     * @return The size in bytes, or 0 for variable-size formats
     */
    private static int getFormatSize(char format, boolean hasNativeSize) {
        switch (format) {
            case 'c': case 'C': case 'x': case 'X': case 'a': case 'A': case 'Z':
                return 1;
            case 's': case 'S': case 'n': case 'v':
                return 2;
            case 'i': case 'I': case 'f': case 'N': case 'V':
                return 4;
            case 'l': case 'L':
                return hasNativeSize ? 8 : 4; // Native long is 8 bytes, regular long is 4
            case 'q': case 'Q': case 'd': case 'j': case 'J':
                return 8;
            case 'w': case 'u': case 'U': case 'p': case 'P': case 'b': case 'B': case 'h': case 'H':
                return 0; // Variable size
            default:
                return 0; // Unknown or variable size
        }
    }

}
