package org.perlonjava.runtime.perlmodule;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.PerlRuntimeTestBase;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.RuntimeList;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class IOTtyRegularFileSourceTest extends PerlRuntimeTestBase {

    @Test
    void fileHandleIsNotMistakenForReusedPtyDescriptorOnEitherBackend() throws Exception {
        String source = """
                use IO::Pty;
                require POSIX;

                {
                    my $pty1 = IO::Pty->new;
                    my $pty2 = IO::Pty->new;
                    my $slave1 = $pty1->slave;
                    my $slave2 = $pty2->slave;
                    $slave1->set_winsize(30, 90, 0, 0);
                    $slave2->clone_winsize_from($slave1);
                }

                {
                    my $pty = IO::Pty->new;
                    my $slave = $pty->slave;
                    $pty->clone_winsize_from($slave)
                        unless POSIX::isatty($pty);
                }

                {
                    my $pty = IO::Pty->new;
                    my $slave = $pty->slave;
                    open my $fh, '<', $0 or die "Cannot open $0: $!";
                    die "regular file resolved to a tty"
                        if defined IO::Tty::ttyname($fh);
                    eval { $slave->clone_winsize_from($fh) };
                    die "clone_winsize_from did not reject regular file: $@"
                        unless $@ =~ /not a tty/i;
                }

                'ok';
                """;

        for (boolean interpreter : new boolean[] { false, true }) {
            PerlLanguageProvider.resetAll();
            CompilerOptions options = new CompilerOptions();
            options.fileName = "src/test/resources/module/IO-Tty/t/clone_winsize.t";
            options.code = source;
            options.useInterpreter = interpreter;

            RuntimeList result = PerlLanguageProvider.executePerlCode(options, false);
            assertEquals("ok", result.scalar().toString(),
                    "regular file source should be rejected with interpreter=" + interpreter);
        }
    }
}
