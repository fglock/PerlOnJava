use strict;
use warnings;
use Test::More;

# Ordinary signed IV comparisons must remain numeric across every public
# comparison operator. The runtime fast path may use native Java longs only
# for this representation; wide values retain the arbitrary-precision path.
my $negative = -17;
my $zero = 0;
my $positive = 42;

is($negative <=> $zero, -1, 'negative native IV sorts before zero');
is($positive <=> $zero, 1, 'positive native IV sorts after zero');
ok($negative < $zero, 'native IV less-than');
ok($negative <= $negative, 'native IV less-than-or-equal');
ok($positive > $zero, 'native IV greater-than');
ok($positive >= $positive, 'native IV greater-than-or-equal');
ok($positive == 42, 'native IV equality');
ok($positive != $negative, 'native IV inequality');

my $maximum_iv = 9_223_372_036_854_775_807;
is($maximum_iv <=> $positive, 1, 'maximum signed IV retains signed ordering');

done_testing;
