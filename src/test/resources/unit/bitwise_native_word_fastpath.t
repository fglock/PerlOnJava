use strict;
use warnings;

use Test::More tests => 4;

# Ordinary numeric bitwise operations keep an unsigned result when bit 63 is
# set.  Reusing that result must preserve the low word through native bitwise
# and shift operations.
my $all_bits = ~0;
is(($all_bits & 0xffff_ffff), 4_294_967_295,
   'AND accepts an unsigned word result');
is(($all_bits ^ $all_bits), 0,
   'XOR accepts an unsigned word result');
is(($all_bits >> 32), 4_294_967_295,
   'right shift accepts an unsigned word result');
is((($all_bits << 1) | 1), '18446744073709551615',
   'chained operations retain unsigned word semantics');
