use strict;
use warnings;
use Test::More tests => 12;

my $two_to_63 = 9.223372036854775808e18;
my $two_to_64 = 1.8446744073709551616e19;

is(unpack('j', pack('j', $two_to_63)), -9223372036854775808,
    'signed quad pack preserves the low bits of an NV at 2**63');
is(unpack('J', pack('J', $two_to_63)), 9223372036854775808,
    'unsigned quad pack preserves an NV at 2**63');
is(unpack('j', pack('j', $two_to_64)), -1,
    'signed quad pack handles an NV at 2**64');
is(unpack('J', pack('J', $two_to_64)), 18446744073709551615,
    'unsigned quad pack saturates an NV at 2**64');

is(pack('j', sprintf('%.18g', 1e50)), pack('j', 1e50),
    'signed quad pack converts a numeric string through NV');
is(pack('j', sprintf('%.18g', -1e50)), pack('j', -1e50),
    'signed quad pack gives numeric strings and NVs the same negative overflow');
is(pack('J', sprintf('%.18g', 1e50)), pack('J', 1e50),
    'unsigned quad pack converts a positive numeric string through NV');
is(pack('J', sprintf('%.18g', -1e50)), pack('J', -1e50),
    'unsigned quad pack gives numeric strings and NVs the same negative overflow');

my $positive_nv = 0 + sprintf('%.18g', 1e50);
my $negative_nv = 0 + sprintf('%.18g', -1e50);
is(pack('j', $positive_nv), pack('j', 1e50),
    'signed quad pack handles numified positive NVs');
is(pack('J', $positive_nv), pack('J', 1e50),
    'unsigned quad pack handles numified positive NVs');
is(pack('j', $negative_nv), pack('j', -1e50),
    'signed quad pack handles numified negative NVs');
is(pack('J', $negative_nv), pack('J', -1e50),
    'unsigned quad pack handles numified negative NVs');
