use strict;
use warnings;
use Test::More tests => 3;

my @package_array;
(@package_array = split //, 'abc') = 1 .. 10;
is_deeply(\@package_array, [1, 2, 3],
    'nested array assignment keeps the inner split length');

(@{\@package_array} = split //, 'abc') = 1 .. 10;
is_deeply(\@package_array, [1, 2, 3],
    'nested dereferenced array assignment keeps the inner split length');

sub split_scalar_and_array_tail {
    my ($head, @tail) = @_;
    return join ':', $head, @tail;
}

is(split_scalar_and_array_tail(qw(one two three)), 'one:two:three',
    'ordinary scalar and array-tail list assignment preserves all arguments');
