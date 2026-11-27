package org.perlonjava.frontend.parser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Tag("unit")
class TryFinallyReturnOwnerTest {
    private static final String PROGRAM = """
            use strict;
            use warnings;
            use feature 'try';
            no warnings 'experimental::try';
            use Scalar::Util qw(weaken);

            {
                package TryFinallyReturnOwnerTestObject;
                our $destroyed = 0;
                sub DESTROY { ++$destroyed }
            }

            our $lvalue_slot = 'before';
            our $lvalue_finally_count = 0;
            sub return_lvalue :lvalue {
                try {
                    return $lvalue_slot;
                }
                catch ($error) { die $error; }
                finally { ++$lvalue_finally_count; }
            }

            my ($weak, $finally_count);
            sub make_object {
                try {
                    my $object = bless {}, 'TryFinallyReturnOwnerTestObject';
                    $weak = $object;
                    weaken($weak);
                    return $object;
                }
                catch ($error) { die $error; }
                finally { ++$finally_count; }
            }

            my $strong = make_object();
            die "return value did not survive finally\\n"
                    unless defined($weak)
                        && $TryFinallyReturnOwnerTestObject::destroyed == 0
                        && $finally_count == 1;
            undef $strong;
            die "final owner release did not destroy the object exactly once\\n"
                    unless !defined($weak)
                        && $TryFinallyReturnOwnerTestObject::destroyed == 1;
            return_lvalue() = 'after';
            die "lvalue return through finally was detached\\n"
                    unless $lvalue_slot eq 'after' && $lvalue_finally_count == 1;
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
    void retainsReturnOwnerAcrossFinallyOnJvmBackend() {
        assertDoesNotThrow(() -> execute(false));
    }

    @Test
    void retainsReturnOwnerAcrossFinallyOnInterpreterBackend() {
        assertDoesNotThrow(() -> execute(true));
    }

    private static void execute(boolean useInterpreter) throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.code = PROGRAM;
        options.fileName = "try_finally_return_owner.t";
        options.useInterpreter = useInterpreter;
        PerlLanguageProvider.executePerlCode(options, true);
    }
}
