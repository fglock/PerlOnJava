use strict;
use warnings;
use Test::More tests => 12;

my $marker_utf8 = my $marker = chr(0xF7);
utf8::upgrade($marker_utf8);

my $bytes = pack('C*', 0x27, 0xC3, 0xB7, 0x27);
ok(!utf8::is_utf8($bytes), 'fixture starts as an octet string');
is(length($bytes), 4, 'fixture contains the encoded marker bytes');

{
    use bytes;
    is($bytes =~ s/$marker_utf8/$marker/g, 1,
        'upgraded pattern matches its encoded octets under use bytes');
}

ok(!utf8::is_utf8($bytes), 'byte substitution preserves octet storage');
is(length($bytes), 3, 'encoded marker contracts to one replacement octet');
is(unpack('H*', $bytes), '27f727', 'replacement has the canonical marker byte');

my $byte_pattern = pack('C*', 0xC3, 0xB7);
my $byte_pattern_target = pack('C*', 0x41, 0xC3, 0xB7, 0x42);
{
    use bytes;
    is($byte_pattern_target =~ s/$byte_pattern/X/g, 1,
        'byte-backed pattern remains a literal octet sequence');
}
is($byte_pattern_target, 'AXB', 'byte-backed pattern consumes exactly two octets');

my $byte_qr = qr/$byte_pattern/;
my $byte_qr_target = pack('C*', 0x41, 0xC3, 0xB7, 0x42);
{
    use bytes;
    is($byte_qr_target =~ s/$byte_qr/Y/g, 1,
        'compiled byte-backed pattern retains its octet provenance');
}
is($byte_qr_target, 'AYB', 'compiled byte-backed pattern consumes two octets');

my $ordinary = "A${marker_utf8}B";
is($ordinary =~ s/$marker_utf8/X/g, 1,
    'ordinary Unicode substitution still matches one character');
is($ordinary, 'AXB', 'ordinary Unicode substitution remains character based');
