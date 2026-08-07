package org.perlonjava.frontend.parser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Tag("unit")
class FutureAsyncAwaitCallbackCleanupTest {
    private static final String PROGRAM = """
            use strict;
            use warnings;

            BEGIN {
                package Future;
                our $VERSION = '0.52';
                $INC{'Future.pm'} = __FILE__;

                sub new { bless {}, shift }
                sub AWAIT_NEW_DONE {
                    shift;
                    bless { values => [ @_ ] }, __PACKAGE__;
                }
                sub AWAIT_WAIT {
                    my $self = shift;
                    return wantarray ? @{ $self->{values} } : $self->{values}[0];
                }
            }

            use Future::AsyncAwait;
            use Scalar::Util qw(weaken);

            async sub clear_callbacks {
                my ($object) = @_;
                $object->{callbacks} = [];
                return 1;
            }

            sub check_nested_cleanup {
                my $weak;
                {
                    my $object = bless { callbacks => [] }, 'CallbackOwner';
                    weaken($weak = $object);
                    push @{ $object->{callbacks} }, sub { my $captured = $object };
                    clear_callbacks($object)->AWAIT_WAIT;
                }

                die "async hash overwrite retained callback capture\n" if defined $weak;
            }

            check_nested_cleanup();
            """;

    @BeforeEach
    void setUp() {
        PerlLanguageProvider.resetAll();
    }

    @AfterEach
    void tearDown() {
        PerlLanguageProvider.resetAll();
    }

    @Test
    void releasesDisplacedCallbackCaptureFromJvmFrontend() {
        assertDoesNotThrow(() -> execute(false));
    }

    @Test
    void releasesDisplacedCallbackCaptureFromInterpreterFrontend() {
        assertDoesNotThrow(() -> execute(true));
    }

    private static void execute(boolean useInterpreter) throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.code = PROGRAM;
        options.fileName = "future_asyncawait_callback_cleanup.t";
        options.useInterpreter = useInterpreter;
        RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));
        PerlLanguageProvider.executePerlCode(options, true);
    }
}
