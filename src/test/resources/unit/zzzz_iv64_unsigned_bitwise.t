use strict;
use warnings;

use Test::More tests => 12;

is(~0, '18446744073709551615', 'numeric NOT zero returns UV_MAX');
is(~1, '18446744073709551614', 'numeric NOT preserves all 64 bits');
ok(~0 > 0, 'UV_MAX compares greater than zero');
is((~0) - 1, '18446744073709551614', 'UV subtraction remains exact');
is((~0) >> 63, 1, 'UV_MAX shifts right through the high bit');
is((~0) >> 32, '4294967295', 'UV_MAX right shift retains lower word');
is(-1 >> 1, '9223372036854775807', 'negative numeric right shift uses UV semantics');
is(-1 << 1, '18446744073709551614', 'negative numeric left shift uses UV semantics');
is(1 << 63, '9223372036854775808', 'numeric left shift returns an unsigned value');
is((1 << 63) | 1, '9223372036854775809', 'numeric OR retains unsigned high bit');
is((~0) & 0xffff, 65535, 'numeric AND masks an unsigned value');
is((~0) ^ 1, '18446744073709551614', 'numeric XOR retains unsigned precision');
