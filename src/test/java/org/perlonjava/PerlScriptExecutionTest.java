package org.perlonjava;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.perlonjava.io.StandardIO;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.scriptengine.PerlLanguageProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for executing Perl scripts and verifying their output.
 * This class uses JUnit 5 for testing and includes parameterized tests
 * to run multiple Perl scripts located in the resources directory.
 */
public class PerlScriptExecutionTest {

    static {
        // Set default locale to US (uses dot as decimal separator)
        // This ensures consistent number formatting across different environments
        Locale.setDefault(Locale.US);
    }

    private PrintStream originalOut; // Stores the original System.out
    private ByteArrayOutputStream outputStream; // Captures the output of the Perl script execution

    /**
     * Provides a stream of Perl script filenames located in the resources directory.
     *
     * @return a Stream of Perl script filenames.
     * @throws IOException if an I/O error occurs while accessing the resources.
     */
    static Stream<String> providePerlScripts() throws IOException {
        // Locate the specific resource directory containing Perl scripts
        URL resourceUrl = PerlScriptExecutionTest.class.getClassLoader().getResource("array.t");
        if (resourceUrl == null) {
            throw new IOException("Resource directory not found");
        }
        Path resourcePath;
        try {
            resourcePath = Paths.get(resourceUrl.toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        // Return a stream of filenames for all Perl scripts in the directory and subdirectories
        return Files.walk(resourcePath)
                .filter(path -> path.toString().endsWith(".t"))
                .map(resourcePath::relativize) // Get the relative path to ensure subdirectory structure is preserved
                .map(Path::toString);
    }

    /**
     * Sets up the test environment by redirecting System.out to a custom output stream.
     * This allows capturing the output of the Perl script execution.
     */
    @BeforeEach
    void setUp() {
        originalOut = System.out;
        outputStream = new ByteArrayOutputStream();

        // Create a new StandardIO with the capture stream
        StandardIO newStdout = new StandardIO(outputStream, true);

        // Replace RuntimeIO.stdout with a new instance
        RuntimeIO.stdout = new RuntimeIO(newStdout);

        // Also update System.out for any direct Java calls
        System.setOut(new PrintStream(outputStream));
    }

    /**
     * Restores the original System.out after each test execution.
     */
    @AfterEach
    void tearDown() {
        // Restore original stdout
        RuntimeIO.stdout = new RuntimeIO(new StandardIO(originalOut, true));
        System.setOut(originalOut);
    }

    /**
     * Gets the root cause of an exception by traversing the cause chain.
     *
     * @param throwable the exception to analyze
     * @return the root cause of the exception
     */
    private Throwable getRootCause(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        return rootCause;
    }

    /**
     * Parameterized test that executes Perl scripts and verifies their output.
     * The test fails if the output contains "not ok" at the beginning of a line,
     * which indicates a failed test in TAP (Test Anything Protocol) format.
     *
     * @param filename the name of the Perl script file to be executed.
     */
    @ParameterizedTest(name = "Test using resource file: {0}")
    @MethodSource("providePerlScripts")
    void testUsingResourceFile(String filename) {
        // Load the Perl script as an InputStream
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(filename);
        assertNotNull(inputStream, "Resource file not found: " + filename);

        try {
            PerlLanguageProvider.resetAll();

            // Read the content of the Perl script with UTF-8 encoding
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            CompilerOptions options = new CompilerOptions();
            options.code = content; // Set the code to be executed
            options.fileName = filename; // Set the filename for reference

            // Add the path to the Perl modules
            RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));

            // Execute the Perl code
            PerlLanguageProvider.executePerlCode(options, true);

            // Capture and verify the output
            String output = outputStream.toString();
            int lineNumber = 0;
            boolean errorFound = false;

            for (String line : output.lines().toList()) {
                lineNumber++;

                // Check for test failures - works with both Test::More TAP format and simple tests
                if (line.trim().startsWith("not ok")) {
                    errorFound = true;
                    fail("Test failure in " + filename + " at line " + lineNumber + ": " + line);
                    break;
                }

                // Check for bail out (TAP format)
                if (line.trim().startsWith("Bail out!")) {
                    fail("Test bailed out in " + filename + " at line " + lineNumber + ": " + line);
                    break;
                }
            }

            if (!errorFound) {
                assertFalse(errorFound, "Output should not contain test failures in " + filename);
            }
        } catch (Exception e) {
            // Get the root cause and print its stack trace
            Throwable rootCause = getRootCause(e);
            System.err.println("Root cause error in " + filename + ":");
            rootCause.printStackTrace(System.err);
            fail("Execution of " + filename + " failed: " + rootCause.getMessage());
        }
    }

    /**
     * Test to verify the availability of a specific resource file.
     * Ensures that the 'array.t' file is present in the resources.
     */
    @Test
    void testResourceAvailability() {
        // Check if the 'array.t' resource file is available
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("array.t");
        assertNotNull(inputStream, "Resource file 'array.t' should be available");
    }
}
