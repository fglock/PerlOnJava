use strict;
use warnings;
use Encode qw(_utf8_on is_utf8);
use Test::More;

my $left = "\xE9";
my $right = "\xF1";
my $byte_result = $left . $right;
is(unpack('H*', $byte_result), 'e9f1', 'byte concat preserves octets');
ok(!is_utf8($byte_result), 'byte concat keeps the UTF-8 flag off');

my $utf8_left = 'A';
_utf8_on($utf8_left);
my $mixed = $utf8_left . $right;
is($mixed, "A\x{F1}", 'mixed concat preserves characters');
ok(is_utf8($mixed), 'mixed concat keeps the UTF-8 flag on');

done_testing;
