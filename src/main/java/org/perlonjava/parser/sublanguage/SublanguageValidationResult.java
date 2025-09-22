package org.perlonjava.parser.sublanguage;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the result of sublanguage validation.
 * 
 * This class encapsulates the validation results from sublanguage parsers,
 * including success/failure status, error messages, and position information
 * for accurate error reporting.
 * 
 * Supports two types of errors:
 * - Syntax errors: User mistakes (invalid syntax, malformed patterns)
 * - Unimplemented errors: Valid Perl but not yet supported in PerlOnJava
 */
public class SublanguageValidationResult {
    
    /**
     * Types of errors that can occur during sublanguage parsing.
     */
    public enum ErrorType {
        /** User syntax error - invalid/malformed input */
        SYNTAX_ERROR,
        /** Valid Perl but not yet implemented in PerlOnJava */
        UNIMPLEMENTED_ERROR
    }
    
    /**
     * Represents a single validation error with type and message.
     */
    public static class ValidationError {
        public final ErrorType type;
        public final String message;
        public final int position;
        
        public ValidationError(ErrorType type, String message, int position) {
            this.type = type;
            this.message = message != null ? message : "";
            this.position = position;
        }
        
        public ValidationError(ErrorType type, String message) {
            this(type, message, -1);
        }
        
        @Override
        public String toString() {
            String prefix = type == ErrorType.SYNTAX_ERROR ? "Syntax error" : "Not yet implemented";
            return position >= 0 ? 
                String.format("%s at position %d: %s", prefix, position, message) :
                String.format("%s: %s", prefix, message);
        }
    }
    
    private final boolean success;
    private final List<ValidationError> errors;
    private final List<String> warnings;
    private final String processedInput;
    
    /**
     * Creates a successful validation result.
     * 
     * @param processedInput The processed/validated input (may be transformed)
     */
    public SublanguageValidationResult(boolean success, String processedInput) {
        this.success = success;
        this.processedInput = processedInput != null ? processedInput : "";
        this.errors = new ArrayList<>();
        this.warnings = new ArrayList<>();
    }
    
    /**
     * Creates a validation result with errors.
     * 
     * @param success Whether validation succeeded
     * @param errors List of validation errors
     * @param warnings List of warning messages
     * @param processedInput The processed input (if any)
     */
    public SublanguageValidationResult(boolean success, List<ValidationError> errors, List<String> warnings, String processedInput) {
        this.success = success;
        this.errors = errors != null ? new ArrayList<>(errors) : new ArrayList<>();
        this.warnings = warnings != null ? new ArrayList<>(warnings) : new ArrayList<>();
        this.processedInput = processedInput != null ? processedInput : "";
    }
    
    /**
     * Creates a failed validation result with a single syntax error.
     * 
     * @param errorMessage The error message
     */
    public static SublanguageValidationResult syntaxError(String errorMessage) {
        SublanguageValidationResult result = new SublanguageValidationResult(false, "");
        result.addSyntaxError(errorMessage);
        return result;
    }
    
    /**
     * Creates a failed validation result with a single syntax error at a position.
     * 
     * @param errorMessage The error message
     * @param position The position where the error occurred
     */
    public static SublanguageValidationResult syntaxError(String errorMessage, int position) {
        SublanguageValidationResult result = new SublanguageValidationResult(false, "");
        result.addSyntaxError(errorMessage, position);
        return result;
    }
    
    /**
     * Creates a failed validation result with an unimplemented feature error.
     * 
     * @param errorMessage The error message describing what's not implemented
     */
    public static SublanguageValidationResult unimplementedError(String errorMessage) {
        SublanguageValidationResult result = new SublanguageValidationResult(false, "");
        result.addUnimplementedError(errorMessage);
        return result;
    }
    
    /**
     * Creates a failed validation result with an unimplemented feature error at a position.
     * 
     * @param errorMessage The error message describing what's not implemented
     * @param position The position where the unimplemented feature was encountered
     */
    public static SublanguageValidationResult unimplementedError(String errorMessage, int position) {
        SublanguageValidationResult result = new SublanguageValidationResult(false, "");
        result.addUnimplementedError(errorMessage, position);
        return result;
    }
    
    /**
     * Creates a successful validation result.
     * 
     * @param processedInput The processed input
     */
    public static SublanguageValidationResult success(String processedInput) {
        return new SublanguageValidationResult(true, processedInput);
    }
    
    /**
     * Check if validation was successful.
     * 
     * @return true if validation succeeded, false if there were errors
     */
    public boolean isSuccess() {
        return success && errors.isEmpty();
    }
    
    /**
     * Check if there are any errors.
     * 
     * @return true if there are errors, false otherwise
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    /**
     * Check if there are any warnings.
     * 
     * @return true if there are warnings, false otherwise
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    /**
     * Get all validation errors.
     * 
     * @return List of validation errors (never null)
     */
    public List<ValidationError> getErrors() {
        return new ArrayList<>(errors);
    }
    
    /**
     * Get all error messages as strings.
     * 
     * @return List of error message strings (never null)
     */
    public List<String> getErrorMessages() {
        List<String> messages = new ArrayList<>();
        for (ValidationError error : errors) {
            messages.add(error.toString());
        }
        return messages;
    }
    
    /**
     * Get only syntax errors.
     * 
     * @return List of syntax errors
     */
    public List<ValidationError> getSyntaxErrors() {
        List<ValidationError> syntaxErrors = new ArrayList<>();
        for (ValidationError error : errors) {
            if (error.type == ErrorType.SYNTAX_ERROR) {
                syntaxErrors.add(error);
            }
        }
        return syntaxErrors;
    }
    
    /**
     * Get only unimplemented feature errors.
     * 
     * @return List of unimplemented errors
     */
    public List<ValidationError> getUnimplementedErrors() {
        List<ValidationError> unimplementedErrors = new ArrayList<>();
        for (ValidationError error : errors) {
            if (error.type == ErrorType.UNIMPLEMENTED_ERROR) {
                unimplementedErrors.add(error);
            }
        }
        return unimplementedErrors;
    }
    
    /**
     * Check if there are any syntax errors.
     * 
     * @return true if there are syntax errors, false otherwise
     */
    public boolean hasSyntaxErrors() {
        return !getSyntaxErrors().isEmpty();
    }
    
    /**
     * Check if there are any unimplemented feature errors.
     * 
     * @return true if there are unimplemented errors, false otherwise
     */
    public boolean hasUnimplementedErrors() {
        return !getUnimplementedErrors().isEmpty();
    }
    
    /**
     * Get all warning messages.
     * 
     * @return List of warning messages (never null)
     */
    public List<String> getWarnings() {
        return new ArrayList<>(warnings);
    }
    
    /**
     * Get the first error message, if any.
     * 
     * @return First error message, or null if no errors
     */
    public String getFirstError() {
        return errors.isEmpty() ? null : errors.get(0).toString();
    }
    
    /**
     * Get the processed input string.
     * This may be the original input or a transformed version.
     * 
     * @return The processed input string
     */
    public String getProcessedInput() {
        return processedInput;
    }
    
    /**
     * Add a syntax error to this result.
     * 
     * @param message The error message to add
     */
    public void addSyntaxError(String message) {
        if (message != null && !message.trim().isEmpty()) {
            errors.add(new ValidationError(ErrorType.SYNTAX_ERROR, message));
        }
    }
    
    /**
     * Add a syntax error with position to this result.
     * 
     * @param message The error message to add
     * @param position The position where the error occurred
     */
    public void addSyntaxError(String message, int position) {
        if (message != null && !message.trim().isEmpty()) {
            errors.add(new ValidationError(ErrorType.SYNTAX_ERROR, message, position));
        }
    }
    
    /**
     * Add an unimplemented feature error to this result.
     * 
     * @param message The error message describing what's not implemented
     */
    public void addUnimplementedError(String message) {
        if (message != null && !message.trim().isEmpty()) {
            errors.add(new ValidationError(ErrorType.UNIMPLEMENTED_ERROR, message));
        }
    }
    
    /**
     * Add an unimplemented feature error with position to this result.
     * 
     * @param message The error message describing what's not implemented
     * @param position The position where the unimplemented feature was encountered
     */
    public void addUnimplementedError(String message, int position) {
        if (message != null && !message.trim().isEmpty()) {
            errors.add(new ValidationError(ErrorType.UNIMPLEMENTED_ERROR, message, position));
        }
    }
    
    /**
     * Add a warning message to this result.
     * 
     * @param warning The warning message to add
     */
    public void addWarning(String warning) {
        if (warning != null && !warning.trim().isEmpty()) {
            warnings.add(warning);
        }
    }
    
    /**
     * Get the total number of errors.
     * 
     * @return Number of errors
     */
    public int getErrorCount() {
        return errors.size();
    }
    
    /**
     * Get the total number of warnings.
     * 
     * @return Number of warnings
     */
    public int getWarningCount() {
        return warnings.size();
    }
    
    /**
     * Get a summary string of the validation result.
     * 
     * @return Summary string including success status and message counts
     */
    public String getSummary() {
        if (isSuccess()) {
            return "Validation successful" + (hasWarnings() ? " with " + getWarningCount() + " warning(s)" : "");
        } else {
            return "Validation failed with " + getErrorCount() + " error(s)" + 
                   (hasWarnings() ? " and " + getWarningCount() + " warning(s)" : "");
        }
    }
    
    /**
     * Get a detailed string representation of all errors and warnings.
     * 
     * @return Detailed string with all messages
     */
    public String getDetailedMessages() {
        StringBuilder sb = new StringBuilder();
        
        if (hasErrors()) {
            sb.append("Errors:\n");
            for (int i = 0; i < errors.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(errors.get(i)).append("\n");
            }
        }
        
        if (hasWarnings()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Warnings:\n");
            for (int i = 0; i < warnings.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(warnings.get(i)).append("\n");
            }
        }
        
        return sb.toString().trim();
    }
    
    /**
     * String representation of this validation result.
     * 
     * @return String representation including summary and messages
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SublanguageValidationResult{");
        sb.append("success=").append(success);
        sb.append(", errors=").append(errors.size());
        sb.append(", warnings=").append(warnings.size());
        sb.append(", processedInput='").append(processedInput).append("'");
        sb.append("}");
        return sb.toString();
    }
}
