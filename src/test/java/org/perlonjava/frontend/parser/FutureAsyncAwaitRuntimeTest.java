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
    private static final String TOPLEVEL_PROGRAM = """
            use strict;
            use warnings;

            BEGIN {
                package Future;
                our $VERSION = '0.52';
                $INC{'Future.pm'} = __FILE__;

                sub done { shift; bless { values => [ @_ ], waits => 0 }, __PACKAGE__ }
                sub failed {
                    shift;
                    bless { error => $_[0], waits => 0 }, __PACKAGE__;
                }
                sub AWAIT_WAIT {
                    my $self = shift;
                    ++$self->{waits};
                    $self->{wait_context} = !defined(wantarray) ? 'void'
                            : wantarray ? 'list' : 'scalar';
                    die $self->{error} if exists $self->{error};
                    return wantarray ? @{ $self->{values} } : $self->{values}[0];
                }
            }

            use Future::AsyncAwait;

            my $scalar_future = Future->done(42);
            my $scalar = await $scalar_future;
            die "file-scope scalar await failed\n"
                    unless $scalar == 42 && $scalar_future->{waits} == 1
                        && $scalar_future->{wait_context} eq 'scalar';

            my $list_future = Future->done(20, 22);
            my @values = await $list_future;
            die "file-scope list await failed\n"
                    unless @values == 2 && $values[0] == 20 && $values[1] == 22
                        && $list_future->{wait_context} eq 'list';

            my $void_future = Future->done(1);
            await $void_future;
            die "file-scope void await failed\n"
                    unless $void_future->{wait_context} eq 'void';

            my $failed_future = Future->failed("file await failed\n");
            my $failed_result = eval { await $failed_future };
            die "file-scope await failure was not catchable\n"
                    unless !defined($failed_result) && $@ eq "file await failed\n";
            """;

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
                    $self->{get_context} = !defined(wantarray) ? 'void'
                            : wantarray ? 'list' : 'scalar';
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

            async sub async_body_context {
                return wantarray ? 'list' : defined(wantarray) ? 'scalar' : 'void';
            }
            die "async body did not run in list context\n"
                    unless async_body_context()->AWAIT_GET eq 'list';

            {
                package FutureSubclass;
                our @ISA = ('Future');
            }
            {
                use Future::AsyncAwait future_class => 'FutureSubclass';
                async sub subclass_result { return 123 }
            }
            die "future_class did not select result class\n"
                    unless subclass_result()->isa('FutureSubclass')
                        && subclass_result()->AWAIT_GET == 123;

            eval q{ async sub invalid_map { map { await $_[0] } 1 } };
            die "await in map was not rejected\n"
                    unless $@ =~ /^await is not allowed inside map /;
            eval q{ async sub invalid_grep { grep { await $_[0] } 1 } };
            die "await in grep was not rejected\n"
                    unless $@ =~ /^await is not allowed inside grep /;
            our $foreach_global;
            eval q{
                async sub invalid_foreach {
                    foreach $foreach_global (1) { await $_[0] }
                }
            };
            die "await in non-lexical foreach was not rejected\n"
                    unless $@ =~ /^await is not allowed inside foreach on non-lexical iterator variable /;

            async sub await_foreach_pairs {
                my @result;
                foreach my ($index, $future) (@_) {
                    await $future;
                    push @result, ($index, $future->AWAIT_GET);
                }
                return \\@result;
            }
            my @pair_futures = (Future->new, Future->new, Future->new);
            my $pair_result = await_foreach_pairs(
                    0 => $pair_futures[0],
                    1 => $pair_futures[1],
                    2 => $pair_futures[2]);
            $pair_futures[0]->AWAIT_DONE('zero');
            $pair_futures[1]->AWAIT_DONE('one');
            $pair_futures[2]->AWAIT_DONE('two');
            die "async foreach list failed\n"
                    unless join(',', @{$pair_result->AWAIT_GET}) eq
                            '0,zero,1,one,2,two';

            my $string_eval_error;
            (async sub {
                eval q{ await $_[0] };
                $string_eval_error = $@;
            })->();
            die "await at string-eval level was not rejected\n"
                    unless $string_eval_error =~ /^await is not allowed inside string eval /;

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

            async sub abandonment_identity { return await $_[0] }
            async sub abandonment_outer {
                return await abandonment_identity($_[0]);
            }
            my $abandoned_source = Future->new;
            my $abandoned_result = abandonment_outer($abandoned_source);
            undef $abandoned_result;
            my $abandonment_warning = '';
            {
                local $SIG{__WARN__} = sub { $abandonment_warning .= join '', @_ };
                $abandoned_source->AWAIT_DONE(1);
            }
            die "abandoned nested async chain reported wrong owner\n"
                    unless $abandonment_warning =~
                        /^Suspended async sub main::abandonment_outer lost its returning future /;

            my $failed = Future->new;
            my $failed_result = add_one($failed);
            $failed->AWAIT_FAIL("await failed\n");
            die "outer Future did not fail\n"
                    unless $failed_result->{state} eq 'failed';
            die "wrong await failure\n"
                    unless $failed_result->{values}[0] eq "await failed\n";

            use Future::AsyncAwait qw(:experimental(cancel));
            my $cancel_block_log = '';
            async sub with_cancel_blocks {
                my $captured = 'captured';
                CANCEL { $cancel_block_log .= "first:$captured"; }
                CANCEL { $cancel_block_log .= 'second:'; }
                await $_[0];
                return 1;
            }
            my $cancel_block_pending = Future->new;
            my $cancel_block_result = with_cancel_blocks($cancel_block_pending);
            $cancel_block_result->AWAIT_CANCEL;
            die "CANCEL blocks did not run in reverse order\n"
                    unless $cancel_block_log eq 'second:first:captured';
            my $completed_cancel_result = with_cancel_blocks(
                    Future->AWAIT_NEW_DONE(1));
            die "CANCEL block ran after normal completion\n"
                    unless $completed_cancel_result->AWAIT_GET == 1
                        && $cancel_block_log eq 'second:first:captured';

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

            our @localized_array = (1);
            our %localized_hash = (outside => 2);
            async sub localized_containers {
                local @localized_array = (40);
                local %localized_hash = (inside => 2);
                my $value = await $_[0];
                return $localized_array[0] + $localized_hash{inside} + $value;
            }
            my $container_pending = Future->new;
            my $container_result = localized_containers($container_pending);
            die "localized containers leaked while suspended\n"
                    unless $localized_array[0] == 1 && $localized_hash{outside} == 2
                        && !exists $localized_hash{inside};
            $container_pending->AWAIT_DONE(5);
            die "localized containers were not restored on resume\n"
                    unless $container_result->AWAIT_GET == 47;
            die "localized containers leaked after completion\n"
                    unless $localized_array[0] == 1 && $localized_hash{outside} == 2
                        && !exists $localized_hash{inside};

            use feature 'defer';
            my $defer_log = '';
            async sub deferred_cleanup {
                defer { $defer_log .= 'done'; }
                my $value = await $_[0];
                return $value;
            }
            my $defer_pending = Future->new;
            my $defer_result = deferred_cleanup($defer_pending);
            die "defer ran while suspended\n" unless $defer_log eq '';
            $defer_pending->AWAIT_DONE(9);
            die "defer did not run at frame exit\n"
                    unless $defer_result->AWAIT_GET == 9 && $defer_log eq 'done';

            my $cancelled = Future->new;
            my $cancelled_result = add_one($cancelled);
            $cancelled_result->AWAIT_CANCEL;
            die "cancellation did not propagate\n"
                    unless $cancelled->AWAIT_IS_CANCELLED;
            $cancelled->AWAIT_DONE(99);
            die "cancelled async sub resumed\n"
                    if $cancelled_result->AWAIT_IS_READY &&
                       !$cancelled_result->AWAIT_IS_CANCELLED;

            my $cancel_defer_log = '';
            async sub cancelled_cleanup {
                defer { $cancel_defer_log .= 'cancelled'; }
                await $_[0];
                return 0;
            }
            my $cancel_defer_pending = Future->new;
            my $cancel_defer_result = cancelled_cleanup($cancel_defer_pending);
            $cancel_defer_result->AWAIT_CANCEL;
            die "defer did not run during cancellation cleanup\n"
                    unless $cancel_defer_log eq 'cancelled';

            $! = 0;
            async sub localized_errno {
                local $! = 42;
                my $value = await $_[0];
                return $! + $value;
            }
            my $errno_pending = Future->new;
            my $errno_result = localized_errno($errno_pending);
            die "errno localization leaked while suspended\n" unless $! == 0;
            $errno_pending->AWAIT_DONE(5);
            die "errno state was not restored on resume\n"
                    unless $errno_result->AWAIT_GET == 47 && $! == 0;

            {
                package AsyncAwaitTie;
                sub TIESCALAR { bless { value => $_[1] }, $_[0] }
                sub FETCH { $_[0]{value} }
                sub STORE { $_[0]{value} = $_[1] }
            }
            our $tied_value;
            tie $tied_value, 'AsyncAwaitTie', 3;
            async sub localized_tied_scalar {
                local $tied_value = 40;
                my $value = await $_[0];
                return $tied_value + $value;
            }
            my $tied_pending = Future->new;
            my $tied_result = localized_tied_scalar($tied_pending);
            die "tied localization leaked while suspended\n"
                    unless $tied_value == 3;
            $tied_pending->AWAIT_DONE(7);
            die "tied state was not restored on resume\n"
                    unless $tied_result->AWAIT_GET == 47 && $tied_value == 3;

            our @destroy_log;
            {
                package AsyncAwaitDestroy;
                sub DESTROY { push @main::destroy_log, 'destroyed' }
            }
            async sub cancelled_destroy {
                my $guard = bless {}, 'AsyncAwaitDestroy';
                await $_[0];
                return 0;
            }
            my $destroy_pending = Future->new;
            my $destroy_result = cancelled_destroy($destroy_pending);
            die "lexical was destroyed while suspended\n" if @destroy_log;
            $destroy_result->AWAIT_CANCEL;
            die "cancelled lexical was not destroyed exactly once\n"
                    unless @destroy_log == 1 && $destroy_log[0] eq 'destroyed';

            async sub eval_await {
                my $value = eval { await $_[0] };
                return defined($value) ? $value + 1 : "caught:$@";
            }
            my $eval_pending = Future->new;
            my $eval_result = eval_await($eval_pending);
            $eval_pending->AWAIT_DONE(10);
            die "await inside eval lost success value\n"
                    unless $eval_result->AWAIT_GET == 11;
            my $eval_failed = Future->new;
            my $eval_failed_result = eval_await($eval_failed);
            $eval_failed->AWAIT_FAIL("eval await failed\n");
            die "await failure was not caught by eval\n"
                    unless $eval_failed_result->AWAIT_GET eq
                           "caught:eval await failed\n";

            use feature 'try';
            my $try_finally_log = '';
            async sub try_await {
                my $value;
                try {
                    $value = await($_[0]) + 1;
                }
                catch ($error) {
                    $value = "caught:$error";
                }
                finally {
                    $try_finally_log .= 'finally;';
                }
                return $value;
            }
            my $try_pending = Future->new;
            my $try_result = try_await($try_pending);
            $try_pending->AWAIT_DONE(41);
            die "await inside try lost success value\n"
                    unless $try_result->AWAIT_GET == 42;
            my $try_failed = Future->new;
            my $try_failed_result = try_await($try_failed);
            $try_failed->AWAIT_FAIL("try await failed\n");
            die "await failure was not caught by catch\n"
                    unless $try_failed_result->AWAIT_GET eq
                           "caught:try await failed\n"
                        && $try_finally_log eq 'finally;finally;';

            async sub loop_await {
                my $sum = 0;
                for my $future (@_) {
                    $sum += await $future;
                }
                return $sum;
            }
            my $loop_first = Future->new;
            my $loop_second = Future->new;
            my $loop_result = loop_await($loop_first, $loop_second);
            $loop_first->AWAIT_DONE(20);
            $loop_second->AWAIT_DONE(22);
            die "loop state did not survive repeated awaits\n"
                    unless $loop_result->AWAIT_GET == 42;

            async sub regex_await {
                "letters42" =~ /^([a-z]+)(\\d+)$/;
                await $_[0];
                return "$1:$2";
            }
            my $regex_pending = Future->new;
            my $regex_result = regex_await($regex_pending);
            "outside7" =~ /^([a-z]+)(\\d+)$/;
            $regex_pending->AWAIT_DONE(1);
            die "regex captures did not survive await\n"
                    unless $regex_result->AWAIT_GET eq 'letters:42';
            die "caller regex captures were not restored\n"
                    unless "$1:$2" eq 'outside:7';

            async sub closure_await {
                my $base = 40;
                my $add = sub { $base + $_[0] };
                await $_[0];
                return $add->(2);
            }
            my $closure_pending = Future->new;
            my $closure_result = closure_await($closure_pending);
            $closure_pending->AWAIT_DONE(1);
            die "closure capture did not survive await\n"
                    unless $closure_result->AWAIT_GET == 42;

            async sub list_context_await {
                my @values = await $_[0];
                return join ':', @values;
            }
            my $list_pending = Future->new;
            my $list_result = list_context_await($list_pending);
            $list_pending->AWAIT_DONE(20, 22);
            die "list context did not survive await\n"
                    unless $list_result->AWAIT_GET eq '20:22'
                        && $list_pending->{get_context} eq 'list';

            async sub void_context_await {
                await $_[0];
                return $_[0]{get_context};
            }
            my $void_pending = Future->new;
            my $void_result = void_context_await($void_pending);
            $void_pending->AWAIT_DONE(42);
            die "void context did not survive await\n"
                    unless $void_result->AWAIT_GET eq 'void';

            use feature 'signatures';

            async sub declared_async;
            async sub declared_async :method ($future, $increment = 1) {
                return await($future) + $increment;
            }
            my $declared_pending = Future->new;
            my $declared_result = declared_async($declared_pending, 2);
            $declared_pending->AWAIT_DONE(40);
            die "async signature/attribute/forward declaration failed\n"
                    unless $declared_result->AWAIT_GET == 42;

            async sub exactly_one($value) { return $value }
            my $too_few_ok = eval { exactly_one(); 1 };
            my $too_few_error = $@;
            die "async signature too-few error was not synchronous\n"
                    unless !$too_few_ok
                        && $too_few_error =~ /^Too few arguments for subroutine 'main::exactly_one'/;
            my $too_many_ok = eval { exactly_one(1, 2); 1 };
            my $too_many_error = $@;
            die "async signature too-many error was not synchronous\n"
                    unless !$too_many_ok
                        && $too_many_error =~ /^Too many arguments for subroutine 'main::exactly_one'/;

            my async sub lexical_async($future) {
                return await($future) + 2;
            }
            my $lexical_pending = Future->new;
            my $lexical_result = lexical_async($lexical_pending);
            $lexical_pending->AWAIT_DONE(40);
            die "lexical async sub failed\n"
                    unless $lexical_result->AWAIT_GET == 42;

            async sub read_scalar_handle {
                my $content = "alpha\nbeta\n";
                open my $fh, '<', \\$content or die "scalar handle open failed: $!";
                my $count = read($fh, my $buffer, 16_384);
                return "$count:$buffer";
            }
            die "read in async interpreter frame used sysread semantics\n"
                    unless read_scalar_handle()->AWAIT_GET eq "11:alpha\nbeta\n";

            sub make_deferred_async {
                return async sub { 42 };
            }
            {
                no Future::AsyncAwait;
                die "deferred async compilation lost definition-time Future availability\n"
                        unless make_deferred_async()->()->AWAIT_GET == 42;
            }

            use feature 'class';
            no warnings 'experimental::class';
            class AsyncAwaitExample {
                async method add($future, $increment = 2) {
                    return await($future) + $increment;
                }
            }
            my $method_pending = Future->new;
            my $method_result = AsyncAwaitExample->new->add($method_pending);
            $method_pending->AWAIT_DONE(40);
            die "async method failed\n"
                    unless $method_result->AWAIT_GET == 42;
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

    @Test
    void waitsForFileScopeAwaitablesFromJvmFrontend() {
        assertDoesNotThrow(() -> execute(TOPLEVEL_PROGRAM, false,
                "future_asyncawait_toplevel.t"));
    }

    @Test
    void waitsForFileScopeAwaitablesFromInterpreterFrontend() {
        assertDoesNotThrow(() -> execute(TOPLEVEL_PROGRAM, true,
                "future_asyncawait_toplevel.t"));
    }

    private static void execute(boolean useInterpreter) throws Exception {
        execute(PROGRAM, useInterpreter, "future_asyncawait_phase2.t");
    }

    private static void execute(String program, boolean useInterpreter,
                                String fileName) throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.code = program;
        options.fileName = fileName;
        options.useInterpreter = useInterpreter;
        RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));
        PerlLanguageProvider.executePerlCode(options, true);
    }
}
