use strict;
use warnings;
use Test::More;
use String::CRC32;

is(crc32(''), 0, 'empty string checksum');
is(crc32('123456789'), 3421780262, 'standard CRC-32 check vector');
is(crc32("\xff"), 4278190080, 'checksum is returned as an unsigned 32-bit value');
is(crc32('56789', crc32('1234')), crc32('123456789'),
   'seeded checksum continues a previous checksum');

my $file_data = "1234\0\xff56789";
open my $fh, '<', \$file_data or die "open scalar filehandle: $!";
binmode $fh;
is(crc32($fh), crc32($file_data), 'reads all bytes from a filehandle');
close $fh;

my $remaining = '56789';
open my $seeded_fh, '<', \$remaining or die "open seeded scalar filehandle: $!";
binmode $seeded_fh;
is(crc32($seeded_fh, crc32('1234')), crc32('123456789'),
   'continues a seeded checksum while reading a filehandle');
close $seeded_fh;

done_testing;
