use strict;
use warnings;
use Test::More;

# Keep the subject large enough to exercise the byte-to-character offset path
# used by Joni while keeping this correctness regression practical in the unit
# suite.
my $subject = 'x' x (1024 * 1024);

ok($subject =~ /^x+\z/, 'anchored greedy match reaches the end of a large subject');

my $captured;
if ($subject =~ /^(x+)\z/) {
    $captured = $1;
}
is(length($captured // ''), length($subject),
    'anchored capture spans the full large subject');

ok($subject =~ /^x/, 'start anchor still matches a large subject');
ok($subject =~ /x+\z/, 'end anchor still matches a large subject');
ok(($subject . 'y') !~ /^x+\z/, 'end anchor rejects a trailing character');

done_testing;
