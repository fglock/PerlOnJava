package org.perlonjava;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.scriptengine.PerlLanguageProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This class contains tests for executing Perl scripts using the PerlLanguageProvider.
 * It uses JUnit 5 for testing and includes a parameterized test that runs all .pl files
 * found in the resources directory.
 */
public class PerlScriptExecutionTest {

    private PrintStream originalOut;
    private ByteArrayOutputStream outputStream;

    /**
     * Provides a stream of Perl script filenames found in the resources directory.
     * This method is used as a MethodSource for the parameterized test.
     *
     * @return A stream of Perl script filenames
     * @throws URISyntaxException If there's an issue with the URI syntax
     * @throws IOException        If there's an I/O error
     */
    static Stream<String> providePerlScripts() throws URISyntaxException, IOException {
        URI uri = PerlScriptExecutionTest.class.getResource("/").toURI();
        Path myPath;
        // Interesting: This code handles both JAR and file system resources
        if (uri.getScheme().equals("jar")) {
            FileSystem fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
            myPath = fileSystem.getPath("/");
        } else {
            myPath = Paths.get(uri);
        }
        return Files.walk(myPath)
                .filter(path -> path.toString().endsWith(".pl"))
                .map(path -> path.getFileName().toString());
    }

    /**
     * Sets up the test environment before each test.
     * This method redirects System.out to a ByteArrayOutputStream for output capturing.
     */
    @BeforeEach
    void setUp() {
        originalOut = System.out;
        outputStream = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outputStream));
        RuntimeIO.setCustomOutputStream(outputStream); // Set custom OutputStream in RuntimeIO
    }

    /**
     * Tears down the test environment after each test.
     * This method restores the original System.out.
     */
    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        RuntimeIO.setCustomOutputStream(System.out); // Reset to original System.out
    }

    /**
     * Parameterized test that executes each Perl script found in the resources directory.
     * It checks that the script execution doesn't produce any "not ok" output, which would indicate a test failure.
     *
     * @param filename The name of the Perl script file to test
     */
    @ParameterizedTest(name = "Test using resource file: {0}")
    @MethodSource("providePerlScripts")
    void testUsingResourceFile(String filename) {
        // Load the file from the resources folder
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(filename);
        assertNotNull(inputStream, "Resource file not found: " + filename);

        try {
            // Read the file content
            String content = new String(inputStream.readAllBytes());
            ArgumentParser.CompilerOptions options = new ArgumentParser.CompilerOptions();
            options.code = content;
            options.fileName = filename;

            // Execute Perl code
            PerlLanguageProvider.executePerlCode(options);

            // Check the captured output for "not ok"
            String output = outputStream.toString();
            assertFalse(output.lines().anyMatch(line -> line.contains("not ok")),
                    "Output should not contain 'not ok' in " + filename);
        } catch (Exception e) {
            // Log the exception for debugging purposes
            e.printStackTrace();

            // Assert on the exception
            fail("Execution of " + filename + " failed: " + e.getMessage());
        }
    }
}