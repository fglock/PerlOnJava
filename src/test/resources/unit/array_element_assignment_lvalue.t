use strict;
use warnings;
use Test::More tests => 3;

my @values;
(($values[2] = 5) = 7);
is($values[2], 7, 'out-of-range assignment result remains the array lvalue');
is(scalar @values, 3, 'out-of-range assignment retains intervening undef slots');
ok(!defined $values[1], 'intervening slot is undef');
