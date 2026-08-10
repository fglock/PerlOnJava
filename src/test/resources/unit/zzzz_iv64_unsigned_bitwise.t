use strict;
use warnings;

use Test::More tests => 17;

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
is(0xffffffffffffffff, '18446744073709551615', 'hex literal preserves UV_MAX');
is(0b1111111111111111111111111111111111111111111111111111111111111111,
   '18446744073709551615', 'binary literal preserves UV_MAX');
is(11 << 18446744073709551615, 0, 'UV_MAX left shift count is not treated as negative');
is(11 >> 18446744073709551615, 0, 'UV_MAX right shift count is not treated as negative');
is(sprintf('%.0f', 0x10000000000000000), '18446744073709551616',
   'hex literal above UV_MAX promotes to an NV');
