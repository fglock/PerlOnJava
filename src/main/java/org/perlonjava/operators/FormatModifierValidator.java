package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;
import java.util.*;

/**
 * Centralized validation for pack/unpack format character and modifier combinations.
 * Uses a table-driven approach for efficient and maintainable validation.
 */
public class FormatModifierValidator {
    
    /**
     * Enum for modifier types
     */
    public enum Modifier {
        LITTLE_ENDIAN('<'),
        BIG_ENDIAN('>'),
        NATIVE_SIZE('!');
        
        private final char symbol;
        
        Modifier(char symbol) {
            this.symbol = symbol;
        }
        
        public char getSymbol() {
            return symbol;
        }
        
        public static Modifier fromChar(char c) {
            for (Modifier m : values()) {
                if (m.symbol == c) {
                    return m;
                }
            }
            return null;
        }
    }
    
    /**
     * Validation rule for a format character
     */
    public static class ValidationRule {
        private final Set<Modifier> allowedModifiers;
        private final Set<Modifier> disallowedModifiers;
        
        public ValidationRule(Set<Modifier> allowed, Set<Modifier> disallowed) {
            this.allowedModifiers = allowed != null ? allowed : Collections.emptySet();
            this.disallowedModifiers = disallowed != null ? disallowed : Collections.emptySet();
        }
        
        public boolean isModifierAllowed(Modifier modifier) {
            if (!disallowedModifiers.isEmpty()) {
                return !disallowedModifiers.contains(modifier);
            }
            return allowedModifiers.isEmpty() || allowedModifiers.contains(modifier);
        }
        
        public Set<Modifier> getAllowedModifiers() {
            return allowedModifiers;
        }
        
        public Set<Modifier> getDisallowedModifiers() {
            return disallowedModifiers;
        }
    }
    
    // Validation table mapping format characters to their validation rules
    private static final Map<Character, ValidationRule> VALIDATION_TABLE = new HashMap<>();
    
    static {
        // Helper sets for common modifier combinations
        Set<Modifier> noModifiers = Collections.emptySet();
        Set<Modifier> allModifiers = EnumSet.allOf(Modifier.class);
        Set<Modifier> endianOnly = EnumSet.of(Modifier.LITTLE_ENDIAN, Modifier.BIG_ENDIAN);
        Set<Modifier> nativeSizeOnly = EnumSet.of(Modifier.NATIVE_SIZE);
        
        // Hex formats (h, H) - no modifiers allowed
        VALIDATION_TABLE.put('h', new ValidationRule(noModifiers, allModifiers));
        VALIDATION_TABLE.put('H', new ValidationRule(noModifiers, allModifiers));
        
        // String formats (a, A, Z) - no modifiers allowed  
        VALIDATION_TABLE.put('a', new ValidationRule(noModifiers, allModifiers));
        VALIDATION_TABLE.put('A', new ValidationRule(noModifiers, allModifiers));
        VALIDATION_TABLE.put('Z', new ValidationRule(noModifiers, allModifiers));
        
        // Quad formats (q, Q) - endianness allowed, native size disallowed
        VALIDATION_TABLE.put('q', new ValidationRule(endianOnly, nativeSizeOnly));
        VALIDATION_TABLE.put('Q', new ValidationRule(endianOnly, nativeSizeOnly));
        
        // Intmax formats (j, J) - endianness allowed, native size disallowed
        VALIDATION_TABLE.put('j', new ValidationRule(endianOnly, nativeSizeOnly));
        VALIDATION_TABLE.put('J', new ValidationRule(endianOnly, nativeSizeOnly));
        
        // Floating-point formats (f, F, d, D) - endianness allowed, native size disallowed
        VALIDATION_TABLE.put('f', new ValidationRule(endianOnly, nativeSizeOnly));
        VALIDATION_TABLE.put('F', new ValidationRule(endianOnly, nativeSizeOnly));
        VALIDATION_TABLE.put('d', new ValidationRule(endianOnly, nativeSizeOnly));
        VALIDATION_TABLE.put('D', new ValidationRule(endianOnly, nativeSizeOnly));
        
        // Pointer formats (p, P) - endianness allowed, native size disallowed
        VALIDATION_TABLE.put('p', new ValidationRule(endianOnly, nativeSizeOnly));
        VALIDATION_TABLE.put('P', new ValidationRule(endianOnly, nativeSizeOnly));
        
        // Network byte order formats (n, N, v, V) - native size allowed, endianness disallowed
        VALIDATION_TABLE.put('n', new ValidationRule(nativeSizeOnly, endianOnly));
        VALIDATION_TABLE.put('N', new ValidationRule(nativeSizeOnly, endianOnly));
        VALIDATION_TABLE.put('v', new ValidationRule(nativeSizeOnly, endianOnly));
        VALIDATION_TABLE.put('V', new ValidationRule(nativeSizeOnly, endianOnly));
        
        // Other formats that allow all modifiers (numeric formats like c, C, s, S, i, I, l, L, etc.)
        // These are not explicitly restricted, so they default to allowing all modifiers
    }
    
    // Valid format characters for each modifier type (based on actual Perl behavior)
    private static final String NATIVE_SIZE_VALID_FORMATS = "sSiIlLxXnNvV@.";
    private static final String ENDIANNESS_VALID_FORMATS = "sSiIlLqQjJfFdDpP(";
    
    /**
     * Validate format character and modifier combinations
     * 
     * @param formatChar The format character
     * @param modifiers List of modifiers in order they appear
     * @param context Context string ("pack" or "unpack")
     * @throws PerlCompilerException if validation fails
     */
    public static void validateFormatModifiers(char formatChar, List<Character> modifiers, String context) {
        ValidationRule rule = VALIDATION_TABLE.get(formatChar);
        if (rule == null) {
            // No specific validation rule - allow all modifiers for this format
            return;
        }
        
        // Check for conflicting endianness modifiers
        boolean hasLittleEndian = modifiers.contains('<');
        boolean hasBigEndian = modifiers.contains('>');
        if (hasLittleEndian && hasBigEndian) {
            throw new PerlCompilerException("Can't use both '<' and '>' after type '" + formatChar + "' in " + context);
        }
        
        // Validate each modifier in order (for proper error precedence)
        for (char modifierChar : modifiers) {
            Modifier modifier = Modifier.fromChar(modifierChar);
            if (modifier != null && !rule.isModifierAllowed(modifier)) {
                String validFormats = getValidFormatsForModifier(modifier);
                throw new PerlCompilerException("'" + modifierChar + "' allowed only after types " + validFormats + " in " + context);
            }
        }
    }
    
    /**
     * Get the list of valid format characters for a modifier (matches Perl's error message format)
     * 
     * @param modifier The modifier to get valid formats for
     * @return String containing all valid format characters for this modifier
     */
    private static String getValidFormatsForModifier(Modifier modifier) {
        switch (modifier) {
            case NATIVE_SIZE:
                return NATIVE_SIZE_VALID_FORMATS;
            case LITTLE_ENDIAN:
            case BIG_ENDIAN:
                return ENDIANNESS_VALID_FORMATS;
            default:
                return "";
        }
    }
    
    /**
     * Check if a format character has validation rules
     * 
     * @param formatChar The format character to check
     * @return true if the format has specific validation rules
     */
    public static boolean hasValidationRules(char formatChar) {
        return VALIDATION_TABLE.containsKey(formatChar);
    }
    
    /**
     * Get validation rule for a format character
     * 
     * @param formatChar The format character
     * @return ValidationRule or null if no specific rules
     */
    public static ValidationRule getValidationRule(char formatChar) {
        return VALIDATION_TABLE.get(formatChar);
    }
}
