use strict;
use warnings;
use Test::More tests => 3;
use Math::BigInt;
use List::Util qw(max);

my $big = Math::BigInt->new(2) ** Math::BigInt->new(65);
is($big->bstr, '36893488147419103232', 'power accepts a Math::BigInt exponent');

is(max($big, 1, 2, 3)->bstr, '36893488147419103232',
    'max keeps a leading BigInt above normal integers');
is(max(1, 2, $big, 3)->bstr, '36893488147419103232',
    'max keeps a later BigInt above normal integers');
