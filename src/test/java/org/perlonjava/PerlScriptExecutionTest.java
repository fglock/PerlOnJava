package org.perlonjava;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.perlonjava.io.StandardIO;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.GlobalVariable;
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
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
        return getPerlScripts(false);
    }

    /**
     * Provides a stream of unit test Perl script filenames (only from unit/ directory).
     * These are fast-running tests.
     *
     * @return a Stream of unit test Perl script filenames.
     * @throws IOException if an I/O error occurs while accessing the resources.
     */
    static Stream<String> provideUnitTestScripts() throws IOException {
        return getPerlScripts(true);
    }

    /**
     * Helper method to get Perl scripts, optionally filtered to unit tests only.
     *
     * @param unitOnly if true, only return scripts from unit/ directory
     * @return a Stream of Perl script filenames.
     * @throws IOException if an I/O error occurs while accessing the resources.
     */
    private static Stream<String> getPerlScripts(boolean unitOnly) throws IOException {
        // Locate the test resources directory by finding a known resource first
        URL resourceUrl = PerlScriptExecutionTest.class.getClassLoader().getResource("unit/array.t");
        if (resourceUrl == null) {
            throw new IOException("Resource directory not found");
        }
        Path resourcePath;
        try {
            // Get the parent directory twice to get to src/test/resources root
            resourcePath = Paths.get(resourceUrl.toURI()).getParent().getParent();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        // Return a stream of filenames for Perl scripts
        Stream<Path> pathStream = Files.walk(resourcePath)
                .filter(path -> path.toString().endsWith(".t"));
        
        if (unitOnly) {
            // Only include tests from the unit/ directory
            pathStream = pathStream.filter(path -> {
                Path relative = resourcePath.relativize(path);
                String pathStr = relative.toString();
                return pathStr.startsWith("unit/") || pathStr.startsWith("unit\\");
            });
        }
        
        List<String> sortedScripts = pathStream
                .map(resourcePath::relativize) // Get the relative path
                .map(Path::toString) // Convert to string path
                .sorted() // Ensure deterministic order
                .collect(Collectors.toList());

        String testFilter = System.getenv("JPERL_TEST_FILTER");
        if (testFilter != null && !testFilter.isEmpty()) {
            sortedScripts = sortedScripts.stream()
                    .filter(s -> s.contains(testFilter))
                    .collect(Collectors.toList());

            if (sortedScripts.isEmpty()) {
                throw new IOException("No tests matched JPERL_TEST_FILTER='" + testFilter + "'");
            }

            return sortedScripts.stream();
        }

        // Sharding logic
        String shardIndexProp = System.getProperty("test.shard.index");
        String shardTotalProp = System.getProperty("test.shard.total");
        
        if (shardIndexProp != null && !shardIndexProp.isEmpty() && 
            shardTotalProp != null && !shardTotalProp.isEmpty()) {
            try {
                int shardIndex = Integer.parseInt(shardIndexProp);
                int shardTotal = Integer.parseInt(shardTotalProp);
                
                // Maven surefire.forkNumber is 1-indexed, convert to 0-indexed
                if (shardIndex >= 1 && shardIndex <= shardTotal) {
                    shardIndex = shardIndex - 1;
                }
                
                if (shardTotal > 1 && shardIndex >= 0 && shardIndex < shardTotal) {
                    System.out.println("Running shard " + (shardIndex + 1) + " of " + shardTotal);
                    final int finalShardIndex = shardIndex;
                    return IntStream.range(0, sortedScripts.size())
                        .filter(i -> i % shardTotal == finalShardIndex)
                        .mapToObj(sortedScripts::get);
                }
            } catch (NumberFormatException e) {
                // Silently fall through to run all tests
            }
        }

        return sortedScripts.stream();
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
        // Keep Perl's global *STDOUT/*STDERR in sync with the RuntimeIO static fields.
        // Some tests call `binmode STDOUT/STDERR` and expect it to affect the real globals.
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.stdout);
        GlobalVariable.getGlobalIO("main::STDERR").setIO(RuntimeIO.stderr);

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
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.stdout);
        GlobalVariable.getGlobalIO("main::STDERR").setIO(RuntimeIO.stderr);
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
     * Parameterized test that executes unit test Perl scripts and verifies their output.
     * Only runs tests from the unit/ directory (fast tests).
     * The test fails if the output contains "not ok" at the beginning of a line,
     * which indicates a failed test in TAP (Test Anything Protocol) format.
     *
     * @param filename the name of the Perl script file to be executed.
     */
    @ParameterizedTest(name = "Unit test: {0}")
    @MethodSource("provideUnitTestScripts")
    @Tag("unit")
    void testUnitTests(String filename) {
        executeTest(filename);
    }

    /**
     * Parameterized test that executes all Perl scripts and verifies their output.
     * Runs all tests including comprehensive module tests (slower).
     * The test fails if the output contains "not ok" at the beginning of a line,
     * which indicates a failed test in TAP (Test Anything Protocol) format.
     *
     * @param filename the name of the Perl script file to be executed.
     */
    @ParameterizedTest(name = "Full test: {0}")
    @MethodSource("providePerlScripts")
    @Tag("full")
    void testAllTests(String filename) {
        executeTest(filename);
    }

    /**
     * Executes a single Perl test file and verifies the output.
     *
     * @param filename the name of the Perl script file to be executed.
     */
    private void executeTest(String filename) {
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
            String msg = rootCause.getMessage();
            if (msg == null || msg.isEmpty()) {
                msg = rootCause.toString();
            }
            fail("Execution of " + filename + " failed: " + msg);
        }
    }

    /**
     * Test to verify the availability of a specific resource file.
     * Ensures that the 'unit/array.t' file is present in the resources.
     */
    @Test
    void testResourceAvailability() {
        // Check if the 'unit/array.t' resource file is available
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("unit/array.t");
        assertNotNull(inputStream, "Resource file 'unit/array.t' should be available");
    }
}
