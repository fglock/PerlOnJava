use strict;
use warnings;
use Test::More;
use String::CRC32;

is(crc32('123456789'), 3421780262, 'CRC-32 check vector');
is(crc32('56789', crc32('1234')), 3421780262,
   'seeded calls produce the same checksum as a single call');

my $bytes = "binary\0\xffdata";
open my $fh, '<', \$bytes or die "open scalar filehandle: $!";
binmode $fh;
is(crc32($fh), crc32($bytes), 'reads byte strings from a filehandle');
close $fh;

done_testing;
