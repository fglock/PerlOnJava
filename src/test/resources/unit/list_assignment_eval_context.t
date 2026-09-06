use strict;
use warnings;
use Test::More;

my @caught = eval { die "expected failure\n" };
is(scalar @caught, 0, 'a failed eval in array assignment returns an empty list');
like($@, qr/expected failure/, 'eval records the error');

my @values = eval { qw(alpha beta) };
is_deeply(\@values, [qw(alpha beta)], 'an array assignment evaluates eval in list context');

done_testing;
