use strict;
use warnings;

use Test::More;

sub inspect_caller {
    return (caller(1))[3];
}

sub tail_inspect {
    goto &inspect_caller;
}

sub run_callback (&) {
    return $_[0]->();
}

sub exception_style (&) {
    my $callback = shift;
    return run_callback { $callback->() };
}

is(
    exception_style { tail_inspect() },
    'main::__ANON__',
    'caller sees anonymous callback through nested prototype blocks and goto',
);

my @eval_block_caller = eval { caller(0) };
is($eval_block_caller[3], '(eval)', 'caller preserves eval BLOCK name');
ok(!$eval_block_caller[4], 'caller reports no arguments for eval BLOCK');

my @eval_string_caller = eval q{ caller(0) };
is($eval_string_caller[3], '(eval)', 'caller preserves eval STRING name');
ok(!$eval_string_caller[4], 'caller reports no arguments for eval STRING');

done_testing;
