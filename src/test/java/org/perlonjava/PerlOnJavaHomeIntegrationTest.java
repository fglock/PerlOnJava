package org.perlonjava;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlOnJavaHomeIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void nestedLauncherDoesNotRequireChildPath() throws Exception {
        Path projectDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        boolean windows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        Path launcher = projectDirectory.resolve(windows ? "jperl.bat" : "jperl");
        Path probe = temporaryDirectory.resolve("empty_path_probe.pl");
        Files.writeString(probe, "local $ENV{PATH} = ''; exit(system($^X, '-e', 'exit 0'));\n");

        ProcessBuilder builder = launcherCommand(windows, launcher, probe);
        builder.directory(projectDirectory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("empty-PATH nested launcher probe timed out");
        }
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.exitValue(), output);
    }

    @Test
    void launcherKeepsRuntimeAndCpanStateInsideExplicitHome() throws Exception {
        Path projectDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        boolean windows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        Path launcher = projectDirectory.resolve(windows ? "jperl.bat" : "jperl");
        assertTrue(Files.isRegularFile(launcher), "missing launcher: " + launcher);

        Path isolatedHome = temporaryDirectory.resolve("isolated-home").toAbsolutePath();
        Path defaultUserHome = temporaryDirectory.resolve("default-user-home").toAbsolutePath();
        Files.createDirectories(defaultUserHome);
        Path probe = writeProbe();

        ProcessBuilder builder = launcherCommand(windows, launcher, probe);
        builder.directory(projectDirectory.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("PERLONJAVA_HOME", isolatedHome.toString());
        environment.put("HOME", defaultUserHome.toString());
        environment.put("USERPROFILE", defaultUserHome.toString());
        environment.put("JPERL_OPTS", "-Duser.home=" + defaultUserHome);

        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("isolated-home probe timed out");
        }
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.exitValue(), output);

        Map<String, Path> values = parsePaths(output);
        assertEquals(isolatedHome, values.get("siteprefix"));
        assertEquals(isolatedHome.resolve("lib"), values.get("installsitelib"));
        assertEquals(isolatedHome.resolve("bin"), values.get("installsitebin"));
        assertEquals(isolatedHome.resolve("man").resolve("man1"), values.get("man1dir"));
        assertEquals(isolatedHome.resolve("lib"), values.get("inc"));
        assertEquals(isolatedHome.resolve("cpan"), values.get("cpan_home"));
        assertEquals(isolatedHome.resolve("cpan"), values.get("cpan_candidate"));
        assertEquals(isolatedHome.resolve("lib"), values.get("makemaker_base"));
        assertEquals(isolatedHome.resolve("lib"), values.get("mm_perlonjava_lib"));

        assertTrue(Files.isDirectory(isolatedHome.resolve("core")), output);
        assertTrue(Files.isDirectory(isolatedHome.resolve("cpan").resolve("prefs")), output);
        assertTrue(Files.isDirectory(isolatedHome.resolve("cpan").resolve("patches")), output);
        assertFalse(Files.exists(defaultUserHome.resolve(".perlonjava")), output);
    }

    private Path writeProbe() throws IOException {
        Path probe = temporaryDirectory.resolve("perlonjava_home_probe.pl");
        Files.writeString(probe, """
                use Config;
                use CPAN::Config;
                require CPAN::HandleConfig;
                require ExtUtils::MakeMaker;
                require ExtUtils::MM_PerlOnJava;
                my ($user_inc) = grep { $_ eq $Config{installsitelib} } @INC;
                print 'siteprefix=', $Config{siteprefix}, "\\n";
                print 'installsitelib=', $Config{installsitelib}, "\\n";
                print 'installsitebin=', $Config{installsitebin}, "\\n";
                print 'man1dir=', $Config{man1dir}, "\\n";
                print 'inc=', ($user_inc || ''), "\\n";
                print 'cpan_home=', $CPAN::Config->{cpan_home}, "\\n";
                print 'cpan_candidate=', scalar(CPAN::HandleConfig::cpan_home_dir_candidates()), "\\n";
                print 'makemaker_base=', ExtUtils::MakeMaker::_default_install_base(), "\\n";
                print 'mm_perlonjava_lib=', ExtUtils::MM_PerlOnJava::_perlonjava_lib(), "\\n";
                """);
        return probe;
    }

    private static ProcessBuilder launcherCommand(boolean windows, Path launcher, Path probe) {
        if (windows) {
            String command = "call \"" + launcher + "\" \"" + probe + "\"";
            return new ProcessBuilder("cmd.exe", "/d", "/s", "/c", command);
        }
        return new ProcessBuilder(launcher.toString(), probe.toString());
    }

    private static Map<String, Path> parsePaths(String output) {
        Map<String, Path> values = new HashMap<>();
        for (String line : output.lines().toList()) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                String key = line.substring(0, separator);
                if (key.equals("siteprefix") || key.equals("installsitelib")
                        || key.equals("installsitebin") || key.equals("man1dir")
                        || key.equals("inc") || key.equals("cpan_home")
                        || key.equals("cpan_candidate") || key.equals("makemaker_base")
                        || key.equals("mm_perlonjava_lib")) {
                    values.put(key, Path.of(line.substring(separator + 1)).toAbsolutePath());
                }
            }
        }
        return values;
    }
}
