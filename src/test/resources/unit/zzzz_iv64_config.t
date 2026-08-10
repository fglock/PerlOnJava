use strict;
use warnings;

use Config;
use Test::More tests => 22;

is($Config{ivsize}, 8, 'Config advertises an eight-byte IV');
is($Config{uvsize}, 8, 'Config advertises an eight-byte UV');
is($Config{sizesize}, 8, 'Config advertises an eight-byte size_t');
is($Config{longsize}, 8, 'Config advertises an eight-byte long');
is($Config{ptrsize}, 8, 'Config advertises an eight-byte pointer');
is($Config{nvsize}, 8, 'Config advertises an eight-byte NV');
like($Config{byteorder}, qr/^(?:12345678|87654321)$/, 'Config byte order covers eight bytes');
is($Config{use64bitint}, 'define', 'Config enables 64-bit integers');
is($Config{d_quad}, 'define', 'Config enables quad integers');
is($Config{ivtype}, 'long', 'Config IV type is long');
is($Config{uvtype}, 'unsigned long', 'Config UV type is unsigned long');
is($Config{i64type}, 'long', 'Config signed 64-bit type is long');
is($Config{u64type}, 'unsigned long', 'Config unsigned 64-bit type is unsigned long');
is($Config{nv_preserves_uv_bits}, 53, 'Config reports double integer precision');
is(9223372036854775807 + 1, '9223372036854775808', 'IV overflow becomes an exact UV');
is(4294967295 * 4294967295, '18446744065119617025', 'multiplication remains exact through UV_MAX');

is(length(pack('j', 1)), 8, 'pack j uses IV width');
is(length(pack('J', 1)), 8, 'pack J uses UV width');
is(unpack('H*', pack('j>', -9223372036854775808)), '8000000000000000', 'pack j> preserves IV_MIN');
is(unpack('H*', pack('J>', 18446744073709551615)), 'ffffffffffffffff', 'pack J> preserves UV_MAX');
is(unpack('j>', pack('H*', '8000000000000000')), '-9223372036854775808', 'unpack j> preserves IV_MIN');
is(unpack('J>', pack('H*', 'ffffffffffffffff')), '18446744073709551615', 'unpack J> preserves UV_MAX');
