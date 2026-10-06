use strict;
use warnings;
use Test::More tests => 4;

my $index = \&CORE::index;
my $rindex = \&CORE::rindex;

is($index->('foffooo', 'o', 2), 4,
    'CORE::index code reference honors an explicit start position');
is($index->('foffooo', 'o'), 1,
    'CORE::index code reference defaults the start position');
is($rindex->('foffooo', 'o'), 6,
    'CORE::rindex code reference defaults to the end of the string');
is($rindex->('foffooo', 'o', 4), 4,
    'CORE::rindex code reference honors an explicit start position');
