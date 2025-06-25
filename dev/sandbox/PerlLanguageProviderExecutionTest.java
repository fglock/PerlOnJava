package org.perlonjava;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.scriptengine.PerlLanguageProvider;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for PerlLanguageProvider.
 * This class tests the execution of Perl code using the PerlLanguageProvider.
 */
public class PerlLanguageProviderExecutionTest {

    private PrintStream originalOut;
    private ByteArrayOutputStream outputStream;

    /**
     * Set up the test environment before each test.
     * This method redirects System.out to a ByteArrayOutputStream for capturing output.
     */
    @BeforeEach
    void setUp() {
        originalOut = System.out;
        outputStream = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outputStream));
        RuntimeIO.setCustomOutputStream(outputStream); // Set custom OutputStream in RuntimeIO
    }

    /**
     * Clean up the test environment after each test.
     * This method restores the original System.out.
     */
    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        RuntimeIO.setCustomOutputStream(System.out); // Reset to original System.out
    }

    /**
     * Test the execution of valid Perl code.
     * This test verifies that the PerlLanguageProvider can execute a simple Perl script
     * and produce the expected output.
     */
    @Test
    public void testExecutePerlCode() {
        PerlLanguageProvider.resetAll();

        // Set up test options
        ArgumentParser.CompilerOptions options = new ArgumentParser.CompilerOptions();
        options.code = "print 'Hello, World!';";
        options.fileName = "test.pl";

        try {
            // Execute the Perl code
            RuntimeList result = PerlLanguageProvider.executePerlCode(options, true);

            // Verify the result
            assertNotNull(result, "Result should not be null");
            // TODO: Add more specific assertions based on the expected behavior of executePerlCode
            // For example: assertTrue(result.contains(expectedElement), "Result should contain the expected element");

            // Check the captured output
            String output = outputStream.toString();
            assertTrue(output.contains("Hello, World!"), "Output should contain 'Hello, World!'");
        } catch (Exception t) {
            fail("Exception should not be thrown: " + t.getMessage());
        }
    }

    /**
     * Test the execution of invalid Perl code.
     * This test verifies that the PerlLanguageProvider throws an exception
     * when trying to execute Perl code with syntax errors.
     */
    @Test
    public void testExecutePerlCodeWithError() {
        PerlLanguageProvider.resetAll();

        // Set up test options with invalid Perl code
        ArgumentParser.CompilerOptions options = new ArgumentParser.CompilerOptions();
        options.code = "print 'Hello, World!' 123;"; // Syntax error in Perl code
        options.fileName = "test_error.pl";

        // Verify that an exception is thrown
        assertThrows(Throwable.class, () -> PerlLanguageProvider.executePerlCode(options, true), "Expected an exception to be thrown");
    }
}