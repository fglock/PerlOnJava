use strict;
use warnings;
use Test::More tests => 1;

my $gmtime = \&CORE::gmtime;
is_deeply([$gmtime->(0)], [0, 0, 0, 1, 0, 70, 4, 0, 0],
    'CORE::gmtime code reference returns UTC epoch components');
