package org.perlonjava;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.RuntimeIO;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

public class PerlLanguageProviderTest {

    private PrintStream originalOut;
    private ByteArrayOutputStream outputStream;

    @BeforeEach
    void setUp() {
        originalOut = System.out;
        outputStream = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outputStream));
        RuntimeIO.setCustomOutputStream(outputStream); // Set custom OutputStream in RuntimeIO
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        RuntimeIO.setCustomOutputStream(System.out); // Reset to original System.out
    }

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

            // Check the captured output
            String output = outputStream.toString();
            assertTrue(output.contains("Hello, World!"), "Output should contain 'Hello, World!'");
        } catch (Exception t) {
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