use strict;
use warnings;
use Test::More;

my $prefix = 'x' x 4097;
my $subject = $prefix . 'Kk';

ok($subject =~ /k/i, 'case-folded map search finds a long-prefix match');
my $first_offset = $-[0];
is($first_offset, length($prefix), 'first map candidate is the first match');
is_deeply([ $subject =~ /k/ig ], [ 'K', 'k' ],
    'global search preserves each eligible byte in order');
unlike($prefix, qr/k/i, 'map search rejects a long prefix without a candidate');

done_testing;
