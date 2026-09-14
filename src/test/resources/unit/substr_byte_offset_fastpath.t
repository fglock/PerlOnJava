use strict;
use warnings;
use Test::More;

my $bytes = pack('C*', 0x41, 0xE9, 0x42, 0xFF, 0x43);

is(unpack('H*', substr($bytes, 1, 3)), 'e942ff',
    'byte-string offsets select octets above ASCII');
is(unpack('H*', substr($bytes, -2)), 'ff43',
    'negative byte-string offset counts from byte length');

substr($bytes, 1, 2) = pack('C*', 0x80, 0x81);
is(unpack('H*', $bytes), '418081ff43',
    'byte-string lvalue replacement preserves byte offsets');
ok(!utf8::is_utf8($bytes), 'byte-string substring path preserves byte flag');

done_testing;
