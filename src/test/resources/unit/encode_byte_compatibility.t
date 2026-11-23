use strict;
use warnings;
use Encode qw(encode decode find_encoding);
use Test::More;

use_ok('Encode::Byte');

my $codec = find_encoding('windows-1252');
ok(defined $codec, 'single-byte charset resolves after loading Encode::Byte');
is(decode('windows-1252', "\x80"), "\x{20ac}", 'Windows-1252 byte decodes');
is(encode('windows-1252', "\x{20ac}"), "\x80", 'Windows-1252 character encodes');

done_testing;
