use strict;
use warnings;

use Test::More tests => 16;

is(sprintf('%lld', 9223372036854775807), '9223372036854775807', '%lld formats IV_MAX');
is(sprintf('%lld', -9223372036854775808), '-9223372036854775808', '%lld formats IV_MIN');
is(sprintf('%lld', 18446744073709551615), '-1', '%lld reinterprets UV_MAX as signed');
is(sprintf('%llu', 18446744073709551615), '18446744073709551615', '%llu formats UV_MAX');
is(sprintf('%llu', 9223372036854775808), '9223372036854775808', '%llu formats the high bit');
is(sprintf('%llu', -1), '18446744073709551615', '%llu reinterprets negative one');
is(sprintf('%llx', 18446744073709551615), 'ffffffffffffffff', '%llx formats UV_MAX');
is(sprintf('%#llX', 9223372036854775808), '0X8000000000000000', '%#llX formats the high bit');
is(sprintf('%llo', 18446744073709551615), '1777777777777777777777', '%llo formats UV_MAX');
is(sprintf('%llb', 9223372036854775808), '1' . ('0' x 63), '%llb formats the high bit');
is(sprintf('%qd', 9223372036854775807), '9223372036854775807', '%qd is a signed quad alias');
is(sprintf('%qu', 18446744073709551615), '18446744073709551615', '%qu is an unsigned quad alias');
is(sprintf('%Ld', 9223372036854775807), '9223372036854775807', '%Ld is a signed quad alias');
is(sprintf('%Lu', 18446744073709551615), '18446744073709551615', '%Lu is an unsigned quad alias');
is(sprintf('%020lld', -1), '-0000000000000000001', 'long signed width and zero padding');
is(sprintf('%.20llu', 1), '00000000000000000001', 'long unsigned precision');
