use strict;
use warnings;
use Test::More;

my $inf = 'Inf' + 0;
my $nan = 'NaN' + 0;

ok(!defined eval { chr($inf) }, 'chr rejects infinity');
like($@, qr/Cannot chr/, 'chr reports infinity');
$inf++;
is($inf, 'Inf', 'increment preserves infinity');
is($nan % $nan, 'NaN', 'modulus preserves NaN');
eval 'for (0 .. $inf) { last }';
like($@, qr/Range iterator outside integer range/, 'range rejects infinity');

done_testing;
