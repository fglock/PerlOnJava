package org.perlonjava.runtime.operators;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class SystemOperatorWindowsCommandTest {

    @Test
    void quotedPerlProgramIsSplitWithoutInvokingCmd() {
        String command = "\"C:\\work tree\\jperl.bat\" -e "
                + "\"binmode STDOUT; print STDOUT pack q(H*), q(82a0ff)\"";

        assertEquals(List.of(
                "C:\\work tree\\jperl.bat",
                "-e",
                "binmode STDOUT; print STDOUT pack q(H*), q(82a0ff)"),
                SystemOperator.splitWindowsDirectCommandWords(command));
    }

    @Test
    void unquotedShellSyntaxStillRequiresCmd() {
        assertNull(SystemOperator.splitWindowsDirectCommandWords("echo value | findstr value"));
        assertNull(SystemOperator.splitWindowsDirectCommandWords("echo %PATH%"));
        assertNull(SystemOperator.splitWindowsDirectCommandWords("\"unterminated"));
    }

    @Test
    void batchArgvPreservesMultipleMultilineEvalPrograms() {
        File currentDirectory = new File("build/a188 work tree").getAbsoluteFile();
        String first = "use re Debug=>\"PARSE\";\n"
                + "eval q{qr{(?<b>\\g{c}}}; print STDERR \"CAUGHT:$@\"";
        String second = "warn \"===\\n\"; split /[.;]+['\"]+/, $_[0]";

        List<String> command = SystemOperator.buildWindowsBatchCommand(
                List.of("jperl.bat", "-e", first, "-e", second),
                currentDirectory);

        assertEquals(List.of(
                "cmd.exe", "/x", "/d", "/c",
                "call \"" + new File(currentDirectory, "jperl.bat").getAbsolutePath()
                        + "\" \"-e\" \"" + first.replace("\"", "\"\"")
                        + "\" \"-e\" \"" + second.replace("\"", "\"\"") + "\""),
                command);
    }

    @Test
    void currentJperlBatchUsesDirectJavaArgv() {
        File currentDirectory = new File("build/a188 work tree").getAbsoluteFile();
        File launcher = new File(currentDirectory, "jperl.bat");
        String first = "use re Debug=>\"PARSE\";\n"
                + "qr/[[:^alpha:]\\x{2C2}]/";
        String second = "warn \"===\\n\"; split /[.;]+['\"]+/, $_[0]";
        List<String> expected = List.of("-e", first, "-e", second);

        List<String> command = SystemOperator.buildWindowsResolvedCommand(
                List.of(launcher.getPath(), "-e", first, "-e", second),
                currentDirectory, launcher.getPath());
        int main = command.indexOf("org.perlonjava.app.cli.Main");

        assertTrue(main > 0, "direct command contains the PerlOnJava CLI main class");
        assertEquals(expected, command.subList(main + 1, command.size()));
        assertFalse(command.contains("cmd.exe"));
    }

    @Test
    void otherBatchRemainsOnCmdPath() {
        File currentDirectory = new File("build/a188 work tree").getAbsoluteFile();
        File launcher = new File(currentDirectory, "jperl.bat");
        File batch = new File(currentDirectory, "argument echo.bat");

        List<String> command = SystemOperator.buildWindowsResolvedCommand(
                List.of(batch.getPath(), "first", "second"),
                currentDirectory, launcher.getPath());

        assertEquals("cmd.exe", command.getFirst());
        assertEquals(List.of("cmd.exe", "/x", "/d", "/c"),
                command.subList(0, 4));
        assertTrue(command.get(4).startsWith("call \"" + batch.getAbsolutePath()));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void resolvedBatchReceivesEveryArgumentByteForByte(@TempDir Path temporary)
            throws Exception {
        Path batch = temporary.resolve("argument echo.bat");
        Files.writeString(batch, "@echo off\r\n"
                + "\"%A188_JAVA%\" -cp \"%A188_CP%\" "
                + "\"org.perlonjava.runtime.operators."
                + "SystemOperatorWindowsCommandTest$ArgumentEcho\" %*\r\n",
                StandardCharsets.UTF_8);

        String first = "use re Debug=>\"PARSE\";\n"
                + "eval q{qr{(?<b>\\g{c}}}; print STDERR \"CAUGHT:$@\"";
        String second = "warn \"===\\n\"; split /[.;]+['\"]+/, $_[0]";
        List<String> expected = List.of("-e", first, "-e", second);
        List<String> command = SystemOperator.resolveCommandForProcessBuilder(
                List.of(batch.toString(), "-e", first, "-e", second),
                temporary.toFile());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("A188_JAVA", Path.of(
                System.getProperty("java.home"), "bin", "java.exe").toString());
        builder.environment().put("A188_CP", System.getProperty("java.class.path"));
        Process process = builder.start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS),
                "batch argument echo completes within 20 seconds");
        String stdout = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), stderr);
        assertEquals(expected.stream().map(SystemOperatorWindowsCommandTest::encode)
                .toList(), stdout.lines().toList());
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    public static final class ArgumentEcho {
        private ArgumentEcho() {
        }

        public static void main(String[] args) {
            for (String arg : args) {
                System.out.println(encode(arg));
            }
        }
    }
}
