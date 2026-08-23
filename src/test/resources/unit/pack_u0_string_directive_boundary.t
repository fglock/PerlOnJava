use strict;
use warnings;
use Test::More tests => 4;

my $upgraded_octets = "\xC3\xBEb";
utf8::upgrade($upgraded_octets);

is(pack("U0a2", $upgraded_octets), "\x{FE}",
    'U0 a-directive decodes upgraded octets once');
is(pack("U0a1a1", "\xC3", "\xBE"), "\x{FE}",
    'U0 UTF-8 sequence may span string directives');
is(pack("U0a2Z0a2", "ab", "ignored", "cd"), "abcd",
    'U0 Z0 contributes no terminator');
is(pack("U0a2U1a2", "ab", 0x100, "cd"), "ab\x{100}cd",
    'U directive splits adjacent U0 byte segments');
