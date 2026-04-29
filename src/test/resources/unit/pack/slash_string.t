#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 7;

# Tests for the "len/Z*" / "len/a*" / "len/A*" pack constructs with byte
# strings (Latin-1 high bytes). Regression for a bug where the slash
# construct used getBytes(UTF-8) to compute the length prefix while
# writeString() emitted ISO-8859-1 bytes, producing a wrong (doubled)
# length and trailing zero padding. This is what BSON::PP triggers via
# pack("V/Z*", $utf8_encoded_string).

my $latin = "\xc3\xa9\xc3\xa9\xc3\xa9";   # 6 bytes, no utf8 flag

is(
    unpack("H*", pack("V/Z*", $latin)),
    "07000000c3a9c3a9c3a900",
    'V/Z* length prefix counts bytes (not re-encoded UTF-8) for Z*',
);

is(
    unpack("H*", pack("V/a*", $latin)),
    "06000000c3a9c3a9c3a9",
    'V/a* length prefix counts bytes for a*',
);

is(
    unpack("H*", pack("V/A*", $latin)),
    "06000000c3a9c3a9c3a9",
    'V/A* length prefix counts bytes for A*',
);

is(
    unpack("H*", pack("n/Z*", $latin)),
    "0007c3a9c3a9c3a900",
    'n/Z* length prefix counts bytes for Z*',
);

# Round-trip through unpack
{
    my $p = pack("V/a*", $latin);
    my ($got) = unpack("V/a*", $p);
    is($got, $latin, 'V/a* round-trips a Latin-1 byte string');
}

# Mirror BSON::PP's exact use: a 0x02 (string) field in a tiny BSON doc.
# Field value is "ééééée" already utf8-encoded to 12 bytes. The BSON
# string framing should report length=13 (12 bytes + NUL) and emit
# exactly 13 bytes of payload.
{
    my $v = "\xc3\xa9" x 6;             # 12 bytes, utf8 flag off
    my $p = pack("V/Z*", $v);
    is(length($p), 4 + 13, 'V/Z* total length is 4 (len prefix) + bytes + NUL');
    is(
        unpack("H*", $p),
        "0d000000" . ("c3a9" x 6) . "00",
        'V/Z* matches BSON wire format',
    );
}
