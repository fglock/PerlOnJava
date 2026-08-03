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

done_testing;
