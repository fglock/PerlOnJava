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
class FutureAsyncAwaitRuntimeTest {
    private static final String PROGRAM = """
            use strict;
            use warnings;

            BEGIN {
                package Future;
                our $VERSION = '0.52';
                $INC{'Future.pm'} = __FILE__;

                sub new { bless { state => 'pending', values => [], callbacks => [] }, shift }
                sub AWAIT_NEW_DONE {
                    my $class = shift;
                    bless { state => 'done', values => [ @_ ], callbacks => [] }, $class;
                }
                sub AWAIT_NEW_FAIL {
                    my $class = shift;
                    bless { state => 'failed', values => [ @_ ], callbacks => [] }, $class;
                }
                sub AWAIT_CLONE { ref($_[0])->new }
                sub AWAIT_IS_READY { $_[0]{state} ne 'pending' }
                sub AWAIT_IS_CANCELLED { $_[0]{state} eq 'cancelled' }
                sub AWAIT_GET {
                    my $self = shift;
                    die $self->{values}[0] if $self->{state} eq 'failed';
                    return wantarray ? @{ $self->{values} } : $self->{values}[0];
                }
                sub AWAIT_ON_READY {
                    my ($self, $callback) = @_;
                    if ($self->AWAIT_IS_READY) { $callback->($self) }
                    else { push @{ $self->{callbacks} }, $callback }
                    return $self;
                }
                sub AWAIT_CHAIN_CANCEL {
                    my ($self, $other) = @_;
                    push @{ $self->{cancel_chain} }, $other;
                    $other->AWAIT_CANCEL if $self->AWAIT_IS_CANCELLED;
                    return $self;
                }
                sub AWAIT_CANCEL {
                    my $self = shift;
                    return $self if $self->AWAIT_IS_READY;
                    $self->{state} = 'cancelled';
                    my @callbacks = @{ $self->{callbacks} };
                    $self->{callbacks} = [];
                    $_->($self) for @callbacks;
                    $_->AWAIT_CANCEL for @{ $self->{cancel_chain} || [] };
                    return $self;
                }
                sub AWAIT_DONE {
                    my $self = shift;
                    $self->{state} = 'done';
                    $self->{values} = [ @_ ];
                    my @callbacks = @{ $self->{callbacks} };
                    $self->{callbacks} = [];
                    $_->($self) for @callbacks;
                    return $self;
                }
                sub AWAIT_FAIL {
                    my $self = shift;
                    $self->{state} = 'failed';
                    $self->{values} = [ @_ ];
                    my @callbacks = @{ $self->{callbacks} };
                    $self->{callbacks} = [];
                    $_->($self) for @callbacks;
                    return $self;
                }
            }

            use Future::AsyncAwait;

            async sub add_one {
                my $value = await $_[0];
                return $value + 1;
            }

            my $ready_result = add_one(Future->AWAIT_NEW_DONE(20));
            die "immediate await did not complete\n"
                    unless $ready_result->AWAIT_IS_READY;
            die "wrong immediate await result\n"
                    unless $ready_result->AWAIT_GET == 21;

            my $pending = Future->new;
            my $pending_result = add_one($pending);
            die "pending await completed early\n"
                    if $pending_result->AWAIT_IS_READY;
            $pending->AWAIT_DONE(41);
            die "pending await did not resume\n"
                    unless $pending_result->AWAIT_IS_READY;
            die "wrong resumed result\n"
                    unless $pending_result->AWAIT_GET == 42;

            async sub add_two {
                my ($first, $second) = @_;
                return await($first) + await($second);
            }

            my $first = Future->new;
            my $second = Future->new;
            my $twice = add_two($first, $second);
            $first->AWAIT_DONE(10);
            die "second await completed early\n" if $twice->AWAIT_IS_READY;
            $second->AWAIT_DONE(32);
            die "wrong repeated-await result\n" unless $twice->AWAIT_GET == 42;

            my $failed = Future->new;
            my $failed_result = add_one($failed);
            $failed->AWAIT_FAIL("await failed\n");
            die "outer Future did not fail\n"
                    unless $failed_result->{state} eq 'failed';
            die "wrong await failure\n"
                    unless $failed_result->{values}[0] eq "await failed\n";

            our $localized_value = 7;
            async sub localized_value {
                local $localized_value = 42;
                my $value = await $_[0];
                return $localized_value + $value;
            }
            my $local_pending = Future->new;
            my $local_result = localized_value($local_pending);
            die "localized value leaked while suspended\n"
                    unless $localized_value == 7;
            $local_pending->AWAIT_DONE(5);
            die "localized value was not restored on resume\n"
                    unless $local_result->AWAIT_GET == 47;
            die "localized value leaked after completion\n"
                    unless $localized_value == 7;

            my $cancelled = Future->new;
            my $cancelled_result = add_one($cancelled);
            $cancelled_result->AWAIT_CANCEL;
            die "cancellation did not propagate\n"
                    unless $cancelled->AWAIT_IS_CANCELLED;
            $cancelled->AWAIT_DONE(99);
            die "cancelled async sub resumed\n"
                    if $cancelled_result->AWAIT_IS_READY &&
                       !$cancelled_result->AWAIT_IS_CANCELLED;
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
    void resumesPendingAwaitablesFromJvmFrontend() {
        assertDoesNotThrow(() -> execute(false));
    }

    @Test
    void resumesPendingAwaitablesFromInterpreterFrontend() {
        assertDoesNotThrow(() -> execute(true));
    }

    private static void execute(boolean useInterpreter) throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.code = PROGRAM;
        options.fileName = "future_asyncawait_phase2.t";
        options.useInterpreter = useInterpreter;
        RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));
        PerlLanguageProvider.executePerlCode(options, true);
    }
}
