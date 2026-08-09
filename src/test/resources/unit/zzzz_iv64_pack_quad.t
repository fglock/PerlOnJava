use strict;
use warnings;

use Test::More tests => 16;

sub echo_first { return $_[0] }

is(echo_first(9223372036854775808), '9223372036854775808', 'UV literal survives a subroutine argument');
is(echo_first(18446744073709551615), '18446744073709551615', 'UV_MAX literal survives a subroutine argument');

is(unpack('H*', pack('q>', -1)), 'ffffffffffffffff', 'pack q> negative one');
is(unpack('H*', pack('q>', -9223372036854775808)), '8000000000000000', 'pack q> IV_MIN');
is(unpack('H*', pack('q>', 9223372036854775807)), '7fffffffffffffff', 'pack q> IV_MAX');
is(unpack('H*', pack('Q>', 18446744073709551615)), 'ffffffffffffffff', 'pack Q> UV_MAX');
is(unpack('H*', pack('Q>', 9223372036854775808)), '8000000000000000', 'pack Q> high bit');
is(unpack('H*', pack('Q<', 72623859790382856)), '0807060504030201', 'pack Q< little endian');

is(unpack('q>', pack('H*', '8000000000000000')), '-9223372036854775808', 'unpack q> IV_MIN');
is(unpack('q>', pack('H*', '7fffffffffffffff')), 9223372036854775807, 'unpack q> IV_MAX');
is(unpack('Q>', pack('H*', 'ffffffffffffffff')), '18446744073709551615', 'unpack Q> UV_MAX');
is(unpack('Q>', pack('H*', '8000000000000000')), '9223372036854775808', 'unpack Q> high bit');
is(unpack('Q<', pack('H*', '0807060504030201')), 72623859790382856, 'unpack Q< little endian');

is_deeply([unpack('q>2', pack('q>2', -1, 1))], [-1, 1], 'q repeat round trip');
is_deeply([unpack('Q>2', pack('Q>2', 9223372036854775808, 18446744073709551615))],
          ['9223372036854775808', '18446744073709551615'], 'Q repeat round trip');
is(length(pack('q', 1)), 8, 'native q has eight-byte width');
