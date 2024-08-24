package org.perlonjava;

import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.scriptengine.PerlLanguageProvider;

import static org.junit.jupiter.api.Assertions.*;

public class PerlLanguageProviderTest {

    @Test
    public void testExecutePerlCode() {
        ArgumentParser.CompilerOptions options = new ArgumentParser.CompilerOptions();
        options.code = "print 'Hello, World!';";
        options.fileName = "test.pl";

        try {
            RuntimeList result = PerlLanguageProvider.executePerlCode(options);

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
        ArgumentParser.CompilerOptions options = new ArgumentParser.CompilerOptions();
        options.code = "print 'Hello, World!' 123;"; // There is an error in Perl code
        options.fileName = "test_error.pl";

        assertThrows(Throwable.class, () -> {
            PerlLanguageProvider.executePerlCode(options);
        }, "Expected an exception to be thrown");
    }
}

