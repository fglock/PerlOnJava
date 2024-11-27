package org.perlonjava;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.scriptengine.PerlLanguageProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.perlonjava.runtime.GlobalVariable.resetAllGlobals;
import static org.perlonjava.scriptengine.PerlLanguageProvider.resetAll;

/**
 * Test class for executing Perl scripts and verifying their output.
 * This class uses JUnit 5 for testing and includes parameterized tests
 * to run multiple Perl scripts located in the resources directory.
 */
public class PerlScriptExecutionTest {

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
        URL resourceUrl = PerlScriptExecutionTest.class.getClassLoader().getResource("array.pl");
        if (resourceUrl == null) {
            throw new IOException("Resource directory not found");
        }
        Path resourcePath;
        try {
            resourcePath = Paths.get(resourceUrl.toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        // Return a stream of filenames for all Perl scripts in the directory
        return Files.walk(resourcePath)
                .filter(path -> path.toString().endsWith(".pl"))
                .map(path -> path.getFileName().toString());
    }

    /**
     * Sets up the test environment by redirecting System.out to a custom output stream.
     * This allows capturing the output of the Perl script execution.
     */
    @BeforeEach
    void setUp() {
        originalOut = System.out; // Save the original System.out
        outputStream = new ByteArrayOutputStream(); // Initialize the custom output stream
        System.setOut(new PrintStream(outputStream)); // Redirect System.out to the custom stream
        RuntimeIO.setCustomOutputStream(outputStream); // Set custom OutputStream in RuntimeIO
    }

    /**
     * Restores the original System.out after each test execution.
     */
    @AfterEach
    void tearDown() {
        System.setOut(originalOut); // Restore the original System.out
        RuntimeIO.setCustomOutputStream(System.out); // Reset to original System.out
    }

    /**
     * Parameterized test that executes Perl scripts and verifies their output.
     * The test fails if the output contains the string "not ok".
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
            resetAll();

            // Read the content of the Perl script
            String content = new String(inputStream.readAllBytes());
            ArgumentParser.CompilerOptions options = new ArgumentParser.CompilerOptions();
            options.code = content; // Set the code to be executed
            options.fileName = filename; // Set the filename for reference

            // Add the path to the Perl modules
            options.inc.push(new RuntimeScalar("src/main/perl/lib"));

            // Execute the Perl code
            PerlLanguageProvider.executePerlCode(options, true);

            // Capture and verify the output
            String output = outputStream.toString();
            int lineNumber = 0;
            boolean errorFound = false;

            for (String line : output.lines().toList()) {
                lineNumber++;
                if (line.contains("not ok")) {
                    errorFound = true;
                    fail("Output contains 'not ok' in " + filename + " at line " + lineNumber + ": " + line);
                    break;
                }
            }

            if (!errorFound) {
                assertFalse(errorFound, "Output should not contain 'not ok' in " + filename);
            }
        } catch (Exception e) {
            e.printStackTrace();
            fail("Execution of " + filename + " failed: " + e.getMessage());
        }
    }

    /**
     * Test to verify the availability of a specific resource file.
     * Ensures that the 'array.pl' file is present in the resources.
     */
    @Test
    void testResourceAvailability() {
        // Check if the 'array.pl' resource file is available
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("array.pl");
        assertNotNull(inputStream, "Resource file 'array.pl' should be available");
    }
}
