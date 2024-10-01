package org.perlonjava;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.scriptengine.PerlLanguageProvider;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

public class PerlExecutionTest {

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

    @ParameterizedTest(name = "Test using resource file: {0}")
    @ValueSource(strings = {
            "demo.pl",
            "numification.pl",
            "operations.pl",
            "wantarray.pl",
            "typeglob.pl",
            "range.pl",
            "flipflop.pl",
            "regex.pl",
            "regexreplace.pl",
            "split.pl",
            "transliterate.pl",
            "array.pl",
            "hash.pl",
            "chomp.pl",
            "pack.pl",
            "unpack.pl"
    })
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
                    "Output should not contain 'not ok'");
        } catch (Exception e) {
            // Log the exception for debugging purposes
            e.printStackTrace();

            // Assert on the exception
            fail("Execution of " + filename + " failed: " + e.getMessage());
        }
    }
}