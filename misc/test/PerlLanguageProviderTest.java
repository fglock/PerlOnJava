package org.perlonjava;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PerlLanguageProviderTest {

    // Helper method to execute a Perl script from a file and return its output
    private String executePerlScriptFromFile(String filePath) throws Exception {
        Path path = Paths.get(filePath);
        String code = new String(Files.readAllBytes(path));
        String fileName = path.getFileName().toString();
        boolean debugEnabled = false;
        boolean tokenizeOnly = false;
        boolean compileOnly = false;
        boolean parseOnly = false;

        RuntimeList result = PerlLanguageProvider.executePerlCode(
                code,
                fileName,
                debugEnabled,
                tokenizeOnly,
                compileOnly,
                parseOnly
        );

        // Assuming the RuntimeList can be converted to a String, representing the output
        return result.toString(); // Modify this as needed based on how result is structured
    }

    @Test
    public void testPerlScriptsFromDisk() throws Exception {
        // Define the directory where the test Perl scripts are stored
        String testScriptsDirectory = "path/to/test/scripts"; // Update this path accordingly

        // List of Perl scripts to test
        List<String> testScripts = List.of("test1.pl", "test2.pl", "test3.pl"); // Add your Perl scripts here

        for (String scriptName : testScripts) {
            String scriptPath = testScriptsDirectory + "/" + scriptName;
            String output = executePerlScriptFromFile(scriptPath);

            // Check that the output contains only "ok" and does not contain "not ok" or errors
            assertTrue(output.contains("ok"), "Output should contain 'ok' lines: " + output);
            assertFalse(output.contains("not ok"), "Output should not contain 'not ok' lines: " + output);
            assertFalse(output.toLowerCase().contains("error"), "Output should not contain errors: " + output);
        }
    }

    @Test
    public void testExecutePerlCodeWithError() {
        String code = "print 'Hello, World!' 123;"; // This Perl code has a syntax error
        String fileName = "test_error.pl";
        boolean debugEnabled = false;
        boolean tokenizeOnly = false;
        boolean compileOnly = false;
        boolean parseOnly = false;

        assertThrows(Throwable.class, () -> {
            PerlLanguageProvider.executePerlCode(
                    code,
                    fileName,
                    debugEnabled,
                    tokenizeOnly,
                    compileOnly,
                    parseOnly
            );
        }, "Expected an exception to be thrown");
    }
}
