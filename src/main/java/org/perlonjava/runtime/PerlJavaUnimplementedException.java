package org.perlonjava.runtime;

/**
 * Exception thrown when PerlOnJava encounters a Perl feature that is not yet implemented
 * in the Java regex engine or other Java components. This is distinct from PerlCompilerException
 * which represents actual Perl syntax errors.
 * 
 * This exception is used to distinguish between:
 * - Known invalid Perl syntax (PerlCompilerException) - should always throw
 * - Unimplemented Perl features (PerlJavaUnimplementedException) - can warn in test mode
 */
public class PerlJavaUnimplementedException extends PerlCompilerException {
    
    public PerlJavaUnimplementedException(String message) {
        super(message);
    }
    
    public PerlJavaUnimplementedException(int tokenIndex, String message, ErrorMessageUtil errorMessageUtil) {
        super(tokenIndex, message, errorMessageUtil);
    }
}
