use strict;
use warnings;
use Test::More;

sub empty_arguments {
    return scalar @_;
}

sub mutate_own_empty_arguments {
    push @_, 'private';
    return scalar @_;
}

is(empty_arguments(), 0, 'direct zero-argument call receives an empty @_');
is(mutate_own_empty_arguments(), 1, 'callee can mutate its own empty @_');
is(empty_arguments(), 0, 'zero-argument frames are fresh rather than shared');

done_testing;
