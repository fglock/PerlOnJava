use strict;
use warnings;
use Test::More;

# This is the semantic boundary for a future read-only `my @copy = @_`
# lowering.  The ordinary list assignment creates independent scalar cells;
# paths which can observe that identity must retain the existing copy.

sub copy_then_mutate_argument {
    my @copy = @_;
    $_[0] = 99;
    return $copy[0];
}

my $caller_value = 7;
is(copy_then_mutate_argument($caller_value), 7,
    'copy remains independent after an argument alias is mutated');
is($caller_value, 99, 'argument alias still mutates the caller');

sub mutate_copy_then_read_argument {
    my @copy = @_;
    $copy[0] = 33;
    return $_[0];
}

$caller_value = 8;
is(mutate_copy_then_read_argument($caller_value), 8,
    'writing the copy does not mutate the argument alias');
is($caller_value, 8, 'caller remains unchanged after writing the copy');

sub copy_reference {
    my @copy = @_;
    return \@copy;
}

$caller_value = 9;
my $copy_ref = copy_reference($caller_value);
$caller_value = 10;
is($copy_ref->[0], 9, 'returned copy reference has an independent lifetime');

sub copy_visible_to_string_eval {
    my @copy = @_;
    return eval '$copy[0]';
}

is(copy_visible_to_string_eval(11), 11,
    'string eval can observe the copied lexical array');

sub copy_captured_by_callback {
    my @copy = @_;
    return sub { $copy[0] };
}

my $callback = copy_captured_by_callback(12);
is($callback->(), 12, 'nested closure retains the copied lexical array');

done_testing;
