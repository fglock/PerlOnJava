use strict;
use warnings;
use Test::More;

my $counter = 257;
my $alias = \$counter;
$counter += 9;
is($counter, 266, 'integer compound assignment updates values beyond the scalar cache');
is($$alias, 266, 'integer compound assignment preserves scalar alias identity');

my $negative = -300;
$negative += 7;
is($negative, -293, 'negative integer compound assignment remains numeric');

my $overflow = 9_223_372_036_854_775_807;
$overflow += 1;
ok($overflow > 9_223_372_036_854_775_807,
    'integer compound assignment preserves overflow promotion');

done_testing;
