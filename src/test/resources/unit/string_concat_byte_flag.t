use strict;
use warnings;
use Test::More;
use utf8 ();

my $left = pack('C', 0xC4);
my $right = pack('C', 0xE9);
my $joined = $left . $right;

is(unpack('H*', $joined), 'c4e9', 'concatenation preserves Latin-1 byte values');
ok(!utf8::is_utf8($joined), 'concatenating byte strings keeps the byte-string flag');

my $counter = 42;
my $ordinary = pack('C', 0xA5) . ':' . $counter;
is(unpack('H*', $ordinary), 'a53a3432',
   'ordinary byte-string and integer concatenation preserves octets');
ok(!utf8::is_utf8($ordinary),
   'ordinary byte-string and integer concatenation keeps the byte-string flag');

done_testing;
