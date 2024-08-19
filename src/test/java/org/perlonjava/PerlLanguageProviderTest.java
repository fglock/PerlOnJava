package org.perlonjava;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PerlLanguageProviderTest {

    @Test
    public void testExecutePerlCode() {
        String code = "print 'Hello, World!';";
        String fileName = "test.pl";
        boolean debugEnabled = false;
        boolean tokenizeOnly = false;
        boolean compileOnly = false;
        boolean parseOnly = false;

        try {
            RuntimeList result = PerlLanguageProvider.executePerlCode(
                code,
                fileName,
                debugEnabled,
                tokenizeOnly,
                compileOnly,
                parseOnly
            );

            // Add assertions to verify the result
            assertNotNull(result, "Result should not be null");
            // Add more specific assertions based on the expected behavior of executePerlCode
            // For example, if the result should contain certain elements:
            // assertTrue(result.contains(expectedElement), "Result should contain the expected element");

        } catch (Throwable t) {
            fail("Exception should not be thrown: " + t.getMessage());
        }
    }

    @Test
    public void testExecutePerlCodeWithError() {
        String code = "print 'Hello, World!';"; // Modify this to cause an error in Perl code
        String fileName = "test_error.pl";
        boolean debugEnabled = false;
        boolean tokenizeOnly = false;
        boolean compileOnly = false;
        boolean parseOnly = false;

        assertThrows(Throwable.class, () -> {
            PerlLanguageProvider.executePerlCode(
                code,
                fileName,
                debugEnabled,
                tokenizeOnly,
                compileOnly,
                parseOnly
            );
        }, "Expected an exception to be thrown");
    }
}

