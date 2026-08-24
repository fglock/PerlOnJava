use strict;
use warnings;
use Test::More;

my $ff = pack('C', 0xff);
my $wide = chr(0x100);
my $byte_upper = pack('C', 0xc0);
my $byte_lower = pack('C', 0xe0);

ok(!utf8::is_utf8($ff), 'ff fixture is byte backed');
ok(utf8::is_utf8($wide), '100 fixture is Unicode backed');

my $ff_qr = qr/$ff/;
ok($ff =~ $ff_qr, 'byte-backed literal remains an octet pattern');
ok($ff =~ /\x{ff}/, 'hex ff remains at the byte boundary');
ok($wide =~ /\x{100}/, 'hex 100 crosses the Unicode boundary');
ok($ff =~ /\o{377}/, 'octal 377 remains at the byte boundary');
ok($wide =~ /\o{400}/, 'octal 400 crosses the Unicode boundary');

ok($byte_upper =~ /(?d:\p{Lowercase})/i,
    'outside property has Unicode fold semantics');
ok($byte_upper =~ /(?d:[\p{Lowercase}_])/i,
    'class property retains the byte-class fold contract');
ok($wide =~ /\N{U+0100}/, 'named character promotes outside a class');
ok($wide =~ /[\N{U+0100}]/, 'named character promotes inside a class');

ok('\\p{Lowercase}' =~ /\Q\p{Lowercase}\E/,
    'quoted property lookalike remains literal');
ok('\\p{Lowercase}' =~ /\\p\{Lowercase\}/,
    'escaped property lookalike remains literal');
ok('A' =~ /(?x:# \p{Lowercase}
             A)/,
    'extended-mode comment lookalike is ignored');
ok($wide =~ /\Q$wide\E/, 'quoted wide literal retains Unicode semantics');

my $wide_qr = qr/\x{100}/;
ok($wide =~ $wide_qr, 'compiled qr reuses promoting semantics');

my $octets = pack('C*', 0x27, 0xc3, 0x80, 0x27);
my $unicode_upper = chr(0xc0);
utf8::upgrade($unicode_upper);
{
    use bytes;
    is($octets =~ s/$unicode_upper/X/g, 1,
        'use bytes substitution recognizes promoted pattern octets');
}
ok(!utf8::is_utf8($octets), 'byte substitution preserves octet storage');
is(length($octets), 3, 'byte substitution preserves byte length accounting');
is(unpack('H*', $octets), '275827',
    'byte substitution produces exact replacement octets');

my $wide_octets = pack('C*', 0x41, 0xc4, 0x80, 0x42);
{
    use bytes;
    is($wide_octets =~ s/$wide/Z/g, 1,
        'above-byte promoted pattern matches its encoded octets');
}
ok(!utf8::is_utf8($wide_octets),
    'above-byte substitution preserves octet storage');
is(unpack('H*', $wide_octets), '415a42',
    'above-byte substitution consumes its complete UTF-8 sequence');

my $byte_target = pack('C*', 0x41, 0xc3, 0x80, 0x42);
my $byte_pattern = pack('C*', 0xc3, 0x80);
{
    use bytes;
    is($byte_target =~ s/$byte_pattern/Y/g, 1,
        'byte-backed substitution retains literal octet provenance');
}
is(unpack('H*', $byte_target), '415942',
    'byte-backed substitution consumes exactly two octets');

done_testing;
