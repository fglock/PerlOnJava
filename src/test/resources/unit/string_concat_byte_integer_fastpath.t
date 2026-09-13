use strict;
use warnings;
use Encode qw(is_utf8);
use Test::More;

my $octet = "\xE9";
my $value = 42;
my $result = $octet . $value;

is(unpack('H*', $result), 'e93432', 'byte string plus integer preserves octets');
ok(!is_utf8($result), 'byte string plus integer keeps the UTF-8 flag off');

done_testing;
