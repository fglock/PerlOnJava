use strict;
use warnings;
use Encode qw(_utf8_on is_utf8);
use Test::More;

my $utf8 = "\x{100}";
_utf8_on($utf8);
my $byte = "\xE9";

my $utf8_left = $utf8 . $byte;
is($utf8_left, "\x{100}\x{e9}", 'UTF-8 plus octet preserves characters');
ok(is_utf8($utf8_left), 'UTF-8 plus octet keeps the UTF-8 flag');

my $utf8_right = $byte . $utf8;
is($utf8_right, "\x{e9}\x{100}", 'octet plus UTF-8 preserves characters');
ok(is_utf8($utf8_right), 'octet plus UTF-8 keeps the UTF-8 flag');

{
    use bytes;
    my $bytes_left = $utf8 . $byte;
    is(unpack('H*', $bytes_left), 'c480e9', 'use bytes keeps UTF-8 bytes raw');
    ok(!is_utf8($bytes_left), 'use bytes leaves the UTF-8 flag off');
}

done_testing;
