package org.perlonjava.runtime.operators.sprintf;

/**
 * Validates sprintf format specifiers according to Perl rules
 */
public class SprintfValidator {

    public SprintfValidationResult validate(FormatSpecifier spec) {
        // Handle %%
        if (spec.conversionChar == '%') {
            if (spec.widthFromArg || spec.precisionFromArg) {
                // %*% is allowed but consumes an argument
                return new SprintfValidationResult(SprintfValidationResult.Status.VALID);
            }
            return new SprintfValidationResult(SprintfValidationResult.Status.VALID);
        }

        // Check for spaces in format (special case - invalid but no INVALID appended)
        if (hasInvalidSpaces(spec)) {
            return new SprintfValidationResult(SprintfValidationResult.Status.INVALID_NO_APPEND, "INVALID");
        }

        // Check if we have no conversion character
        if (spec.conversionChar == '\0') {
            return new SprintfValidationResult(SprintfValidationResult.Status.INVALID_APPEND_ERROR, "INVALID");
        }

        // Check for invalid conversion characters
        String validChars = "diouxXeEfFgGaAbBcspn%vDUO";
        if (validChars.indexOf(spec.conversionChar) < 0) {
            return new SprintfValidationResult(SprintfValidationResult.Status.INVALID_APPEND_ERROR, "INVALID");
        }

        // Special case: V length modifier is silently ignored
        if ("V".equals(spec.lengthModifier)) {
            spec.lengthModifier = null;
        }

        // Validate # flag usage
        if (spec.flags.contains("#")) {
            SprintfValidationResult flagResult = validateHashFlag(spec);
            if (!flagResult.isValid()) {
                return flagResult;
            }
        }

        // Validate %n
        if (spec.conversionChar == 'n') {
            // %n is technically valid but we'll handle it specially in the operator
            return new SprintfValidationResult(SprintfValidationResult.Status.VALID);
        }

        // Validate length modifier combinations
        if (spec.lengthModifier != null) {
            SprintfValidationResult lengthResult = validateLengthModifier(spec);
            if (!lengthResult.isValid()) {
                return lengthResult;
            }
        }

        // Validate vector flag combinations
        if (spec.vectorFlag) {
            SprintfValidationResult vectorResult = validateVectorFormat(spec);
            if (!vectorResult.isValid()) {
                return vectorResult;
            }
        }

        // Validate flag combinations
        SprintfValidationResult flagResult = validateFlagCombinations(spec);
        if (!flagResult.isValid()) {
            return flagResult;
        }

        // Check for invalid positional parameter combinations
        if (spec.parameterIndex != null && spec.widthFromArg && spec.widthArgIndex != null) {
            return new SprintfValidationResult(SprintfValidationResult.Status.INVALID_APPEND_ERROR, "INVALID");
        }

        // Check for parameter index issues
        if (spec.parameterIndex != null && spec.parameterIndex == 0) {
            return new SprintfValidationResult(SprintfValidationResult.Status.INVALID_APPEND_ERROR, "INVALID");
        }

        // Check for invalid width/precision parameter indices
        if ((spec.widthArgIndex != null && spec.widthArgIndex == 0) ||
                (spec.precisionArgIndex != null && spec.precisionArgIndex == 0)) {
            return new SprintfValidationResult(SprintfValidationResult.Status.INVALID_APPEND_ERROR, "INVALID");
        }

        return new SprintfValidationResult(SprintfValidationResult.Status.VALID);
    }

    private boolean hasInvalidSpaces(FormatSpecifier spec) {
        // Check for patterns like %6. 6s, %6 .6s, %6.6 s
        if (spec.raw.matches("%[^%]*\\s+[^%]*")) {
            return true;
        }

        // Check for vector formats with spaces like %v. 3d, %0v3 d
        return spec.vectorFlag && spec.raw.contains(" ");
    }

    private SprintfValidationResult validateHashFlag(FormatSpecifier spec) {
        // # flag is only valid for o, x, X, b, B, e, E, f, F, g, G, a, A
        String validHashConversions = "oxXbBeEfFgGaA";
        if (validHashConversions.indexOf(spec.conversionChar) < 0) {
            return new SprintfValidationResult(SprintfValidationResult.Status.INVALID_APPEND_ERROR, "INVALID");
        }
        return new SprintfValidationResult(SprintfValidationResult.Status.VALID);
    }

    private SprintfValidationResult validateLengthModifier(FormatSpecifier spec) {
        String combo = spec.lengthModifier + spec.conversionChar;
        // h with floating point is invalid
        if ("hf".equals(combo) || "hF".equals(combo) || "hg".equals(combo) ||
                "hG".equals(combo) || "he".equals(combo) || "hE".equals(combo) ||
                "ha".equals(combo) || "hA".equals(combo)) {
            return new SprintfValidationResult(SprintfValidationResult.Status.INVALID_APPEND_ERROR, "INVALID");
        }

        // V is not a valid length modifier
        if (spec.lengthModifier.equals("V")) {
            return new SprintfValidationResult(SprintfValidationResult.Status.INVALID_APPEND_ERROR, "INVALID");
        }

        return new SprintfValidationResult(SprintfValidationResult.Status.VALID);
    }

    private SprintfValidationResult validateVectorFormat(FormatSpecifier spec) {
        // Vector flag is only valid with certain conversions
        String validVectorConversions = "diouxXbBcsaAeEfFgG";
        if (validVectorConversions.indexOf(spec.conversionChar) < 0) {
            return new SprintfValidationResult(SprintfValidationResult.Status.INVALID_APPEND_ERROR, "INVALID");
        }

        // Special handling for vector formats with spaces
        if (spec.raw.contains(" ")) {
            // These should be INVALID_NO_APPEND
            return new SprintfValidationResult(SprintfValidationResult.Status.INVALID_NO_APPEND, "INVALID");
        }

        return new SprintfValidationResult(SprintfValidationResult.Status.VALID);
    }

    private SprintfValidationResult validateFlagCombinations(FormatSpecifier spec) {
        // + and space flags are ignored for unsigned conversions
        boolean isUnsigned = "uUoOxXbB".indexOf(spec.conversionChar) >= 0;

        // For %c, # flag is invalid
        if (spec.conversionChar == 'c' && spec.flags.contains("#")) {
            return new SprintfValidationResult(SprintfValidationResult.Status.INVALID_APPEND_ERROR, "INVALID");
        }

        // Space flag with certain conversions
        if (spec.flags.contains(" ")) {
            // Space flag is invalid with %c
            if (spec.conversionChar == 'c') {
                return new SprintfValidationResult(SprintfValidationResult.Status.INVALID_APPEND_ERROR, "INVALID");
            }
        }

        return new SprintfValidationResult(SprintfValidationResult.Status.VALID);
    }
}