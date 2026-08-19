use strict;
use warnings;
use utf8;
use Test::More;

my $byte_sharp = chr 0xDF;
utf8::downgrade($byte_sharp, 1);
my $byte_pair = $byte_sharp . $byte_sharp;
utf8::downgrade($byte_pair, 1);
my $byte_a_umlaut = chr 0xE4;
my $byte_A_umlaut = chr 0xC4;
utf8::downgrade($byte_a_umlaut, 1);
utf8::downgrade($byte_A_umlaut, 1);
my $unicode_sharp = chr 0xDF;
utf8::upgrade($unicode_sharp);

unlike('ss', qr/^$byte_sharp$/di,
    'byte /d literal rejects sharp-s full fold');
unlike($byte_sharp, qr/^ss$/di,
    'byte /d reverse literal rejects sharp-s full fold');
unlike($byte_A_umlaut, qr/^$byte_a_umlaut$/di,
    'byte /d rejects Latin-1 character folding');
like($byte_pair, qr/^($byte_sharp)\1$/di,
    'byte /d numbered backreference retains byte capture');
like($byte_pair, qr/^(?<letter>$byte_sharp)\k<letter>$/di,
    'byte /d named backreference retains byte capture');

like('ss', qr/^$unicode_sharp$/di,
    'character /d literal retains sharp-s full fold');
like($unicode_sharp, qr/^ss$/di,
    'character /d reverse literal retains sharp-s full fold');
like('ss', qr/^\x{DF}$/ui,
    '/u retains sharp-s full fold');
like('ss', qr/^\x{DF}$/ai,
    '/a retains sharp-s full fold');
unlike('ss', qr/^\x{DF}$/aai,
    '/aa rejects sharp-s ASCII crossing');

like('ss', qr/^(?i:$unicode_sharp)$/,
    'scoped /i enables full fold');
unlike('ss', qr/^(?aa-i:\x{DF})$/i,
    'scoped minus i disables outer folding');
unlike('ss', qr/^(?aa:\x{DF})$/i,
    'scoped /aa blocks outer full fold');

my $kelvin = "\N{KELVIN SIGN}";
like($kelvin, qr/^\p{Lowercase}$/i,
    'ignore-case property closure includes Kelvin sign');
like($kelvin, qr/^\p{Lowercase}$/aai,
    'ascii-strict property closure retains Unicode property membership');
like('A', qr/^[\p{Lowercase}]$/i,
    'ignore-case class property closure includes uppercase sibling');

done_testing;
