use strict;
use warnings;
use Test::More tests => 2;

my @values = eval q{ ((['a'], ['b']) x 2) };

is(scalar(@values), 4, 'eval STRING repeats a parenthesized list as a list');
is_deeply([map { $_->[0] } @values], [qw(a b a b)],
    'list repetition preserves the repeated values');
