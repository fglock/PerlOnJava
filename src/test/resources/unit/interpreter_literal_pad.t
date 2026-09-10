use strict;
use warnings;
use Test::More;

# A literal occurrence owns its pos()/\G state.  Re-evaluating the same
# occurrence in a loop must advance /g rather than creating a new scalar.
my $count = 0;
while ("abc" =~ /./g) {
    ++$count;
}
is($count, 3, '/g advances on a literal occurrence');

# Passing a literal by alias keeps Perl's read-only argument behavior.
sub overwrite_first_argument {
    $_[0] = 'changed';
}
my $ok = eval {
    overwrite_first_argument('original');
    1;
};
ok(!$ok, 'assignment through a literal argument dies');
like($@, qr/Modification of a read-only value/,
    'literal argument retains its read-only diagnostic');

done_testing;
