package org.perlonjava;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for executing bundled CPAN module tests.
 * 
 * Module tests live under src/test/resources/module/{ModuleName}/t/
 * with supporting files (samples, data) in sibling directories.
 * 
 * Each test is executed with chdir to the module's root directory
 * so that relative paths (e.g., "samples/foo.xml", "t/ext.ent")
 * resolve correctly.
 * 
 * Unlike unit tests (which are self-contained), module tests may
 * reference external data files and assume a specific working directory.
 */
public class ModuleTestExecutionTest {

    static {
        Locale.setDefault(Locale.US);
    }

    private PrintStream originalOut;
    private ByteArrayOutputStream outputStream;
    private String originalUserDir;

    /**
     * Provides a stream of module test file paths (relative to resources root).
     * Discovers all .t files under module test directories.
     */
    static Stream<String> provideModuleTestScripts() throws IOException {
        URL resourceUrl = ModuleTestExecutionTest.class.getClassLoader().getResource("module");
        if (resourceUrl == null) {
            // No module tests directory - return empty stream
            return Stream.empty();
        }

        Path modulesRoot;
        try {
            modulesRoot = Paths.get(resourceUrl.toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        // Find the resources root (parent of "module/")
        Path resourcesRoot = modulesRoot.getParent();

        List<String> sortedScripts = Files.walk(modulesRoot)
                .filter(path -> path.toString().endsWith(".t"))
                .map(resourcesRoot::relativize)
                .map(Path::toString)
                .sorted()
                .collect(Collectors.toList());

        String testFilter = System.getenv("JPERL_TEST_FILTER");
        if (testFilter != null && !testFilter.isEmpty()) {
            sortedScripts = sortedScripts.stream()
                    .filter(s -> s.contains(testFilter))
                    .collect(Collectors.toList());
            if (sortedScripts.isEmpty()) {
                throw new IOException("No module tests matched JPERL_TEST_FILTER='" + testFilter + "'");
            }
        }

        return sortedScripts.stream();
    }

    @BeforeEach
    void setUp() {
        originalOut = System.out;
        outputStream = new ByteArrayOutputStream();
        originalUserDir = System.getProperty("user.dir");

        StandardIO newStdout = new StandardIO(outputStream, true);
        RuntimeIO.stdout = new RuntimeIO(newStdout);
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.stdout);
        GlobalVariable.getGlobalIO("main::STDERR").setIO(RuntimeIO.stderr);
        System.setOut(new PrintStream(outputStream));
    }

    @AfterEach
    void tearDown() {
        // Restore original stdout
        RuntimeIO.stdout = new RuntimeIO(new StandardIO(originalOut, true));
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.stdout);
        GlobalVariable.getGlobalIO("main::STDERR").setIO(RuntimeIO.stderr);
        System.setOut(originalOut);

        // Restore original working directory
        System.setProperty("user.dir", originalUserDir);
    }

    /**
     * Parameterized test that executes bundled module tests.
     * Each test runs with chdir set to the module's root directory.
     */
    @ParameterizedTest(name = "Module test: {0}")
    @MethodSource("provideModuleTestScripts")
    @Tag("module")
    void testModuleTests(String filename) {
        executeModuleTest(filename);
    }

    /**
     * Resolves the filesystem path for a module directory from a test resource path.
     * For example, "module/XML-Parser/t/file.t" -> filesystem path of "module/XML-Parser/"
     */
    private Path resolveModuleDir(String filename) {
        // filename is like "module/XML-Parser/t/file.t"
        // We need the filesystem path of "module/XML-Parser/"
        // Extract module name from path: module/{name}/t/...
        String[] parts = filename.split("[/\\\\]");
        if (parts.length < 3 || !parts[0].equals("module")) {
            throw new IllegalArgumentException("Invalid module test path: " + filename);
        }
        String moduleName = parts[1];
        String resourceDir = "module/" + moduleName;

        URL dirUrl = getClass().getClassLoader().getResource(resourceDir);
        if (dirUrl == null) {
            throw new IllegalStateException("Module directory not found in resources: " + resourceDir);
        }

        try {
            return Paths.get(dirUrl.toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private Throwable getRootCause(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        return rootCause;
    }

    /**
     * Executes a single module test file.
     * Sets the working directory to the module's root before execution
     * so relative paths in tests resolve correctly.
     */
    private void executeModuleTest(String filename) {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(filename);
        assertNotNull(inputStream, "Resource file not found: " + filename);

        try {
            PerlLanguageProvider.resetAll();

            // Resolve the module directory and chdir to it
            Path moduleDir = resolveModuleDir(filename);
            System.setProperty("user.dir", moduleDir.toAbsolutePath().toString());

            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            if (content.indexOf('\r') >= 0) {
                content = content.replace("\r\n", "\n").replace("\r", "\n");
            }

            CompilerOptions options = new CompilerOptions();
            options.code = content;
            options.fileName = filename;

            // Add the path to the Perl modules (absolute path since we changed CWD)
            Path perlLibPath = Paths.get(originalUserDir, "src/main/perl/lib");
            RuntimeArray.push(options.inc, new RuntimeScalar(perlLibPath.toString()));

            PerlLanguageProvider.executePerlCode(options, true);

            // Verify TAP output
            String output = outputStream.toString();
            int lineNumber = 0;

            for (String line : output.lines().toList()) {
                lineNumber++;

                if (line.trim().startsWith("not ok") && !line.contains("# TODO")) {
                    fail("Test failure in " + filename + " at line " + lineNumber + ": " + line);
                    break;
                }

                if (line.trim().startsWith("Bail out!")) {
                    fail("Test bailed out in " + filename + " at line " + lineNumber + ": " + line);
                    break;
                }
            }
        } catch (Exception e) {
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
}
