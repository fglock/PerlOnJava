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

public class PerlExecutionTest {

    private PrintStream originalOut;
    private ByteArrayOutputStream outputStream;

    static Stream<String> providePerlScripts() throws URISyntaxException, IOException {
        URI uri = PerlExecutionTest.class.getResource("/").toURI();
        Path myPath;
        if (uri.getScheme().equals("jar")) {
            FileSystem fileSystem = FileSystems.newFileSystem(uri, Collections.<String, Object>emptyMap());
            myPath = fileSystem.getPath("/");
        } else {
            myPath = Paths.get(uri);
        }
        return Files.walk(myPath)
                .filter(path -> path.toString().endsWith(".pl"))
                .map(path -> path.getFileName().toString());
    }

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

    @ParameterizedTest(name = "Test using resource file: {0}")
    @MethodSource("providePerlScripts")
    void testUsingResourceFile(String filename) {
        // Load the file from the resources folder
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(filename);
        assertNotNull(inputStream);

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