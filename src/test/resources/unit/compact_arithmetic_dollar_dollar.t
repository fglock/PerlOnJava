use strict;
use warnings;
use Test::More;

my $l_random = 42;
my $pid = $$;
$l_random += int(rand($$*$l_random/2*5)) + $$;

my $minimum = 42 + $pid;
my $maximum = $minimum + int($pid * 42 / 2 * 5);
cmp_ok($l_random, '>=', $minimum, 'compact arithmetic with $$ and scalar division parses');
cmp_ok($l_random, '<=', $maximum, 'compact arithmetic retains rand expression semantics');

done_testing;
