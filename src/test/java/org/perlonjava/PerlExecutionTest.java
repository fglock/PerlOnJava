package org.perlonjava;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.perlonjava.scriptengine.PerlLanguageProvider;

import java.io.*;
import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;


@RunWith(Parameterized.class)
public class PerlExecutionTest {

    private final String filename;

    public PerlExecutionTest(String filename) {
        this.filename = filename;
    }

    @Parameterized.Parameters(name = "{index}: testUsingResourceFile({0})")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {"demo.pl"},
                {"operations.pl"},
                {"wantarray.pl"},
                {"typeglob.pl"},
                {"regex.pl"},
                {"regexreplace.pl"},
                {"split.pl"}
        });
    }

    @Test
    public void testUsingResourceFile() throws Exception {
        // Load the file from the resources folder
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(filename);
        assertNotNull(inputStream);

        // Read the file content
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }

        ArgumentParser.CompilerOptions options = new ArgumentParser.CompilerOptions();
        options.code = sb.toString();
        options.fileName = filename;

        // Capture the output of the Perl execution
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(outputStream));

        try {
            PerlLanguageProvider.executePerlCode(options);

            // Restore the original System.out
            System.setOut(originalOut);

            // Check the captured output for "not ok"
            String output = outputStream.toString();
            boolean containsNotOk = false;
            try (BufferedReader outputReader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(output.getBytes())))) {
                String outputLine;
                while ((outputLine = outputReader.readLine()) != null) {
                    if (outputLine.contains("not ok")) {
                        containsNotOk = true;
                        System.out.println("Debug: Found 'not ok' in output line: " + outputLine);
                        break;
                    }
                }
            }
            assertFalse("Output should not contain 'not ok'", containsNotOk);

        } catch (Throwable t) {
            // Restore the original System.out in case of an exception
            System.setOut(originalOut);
            fail("Exception should not be thrown: " + t.getMessage());
        }
    }
}

