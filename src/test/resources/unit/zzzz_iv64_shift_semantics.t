use strict;
use warnings;

use Test::More tests => 10;

is(1 << 40, '1099511627776', 'numeric left shift uses more than 32 bits');
is((1 << 40) >> 8, '4294967296', 'numeric right shift retains high bits');
is(3 << 40, '3298534883328', 'numeric shift preserves the operand bits');
is(1 << -40, 0, 'negative left shift reverses to right shift');
is((1 << 40) >> -8, '281474976710656',
    'negative right shift reverses to left shift');

{
    use integer;
    is(~3, -4, 'integer numeric NOT uses signed 64-bit semantics');
    is(1 << 40, '1099511627776', 'integer left shift uses 64-bit width');
    is((1 << 40) >> 8, '4294967296', 'integer right shift uses 64-bit width');
    is(-1 >> 40, -1, 'integer right shift propagates the sign bit');
    is(1 << 63, '-9223372036854775808',
        'integer left shift reaches the signed high bit');
}
