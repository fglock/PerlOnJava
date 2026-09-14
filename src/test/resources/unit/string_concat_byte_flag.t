use strict;
use warnings;
use Test::More;
use utf8 ();

my $left = pack('C', 0xC4);
my $right = pack('C', 0xE9);
my $joined = $left . $right;

is(unpack('H*', $joined), 'c4e9', 'concatenation preserves Latin-1 byte values');
ok(!utf8::is_utf8($joined), 'concatenating byte strings keeps the byte-string flag');

done_testing;
