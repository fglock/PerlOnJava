use strict;
use warnings;
use Test::More;

ok('baaa' =~ /((?(1)a|b))+/, 'repeated capture condition matches');
is($&, $] >= 5.037010 ? 'baaa' : 'b',
    'condition sees the preceding iteration while its capture is open');

done_testing;
