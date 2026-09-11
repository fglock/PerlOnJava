use strict;
use warnings;
use Test::More;

# Numeric bitwise operations may use an internal unsigned representation, but
# results that fit a native signed IV retain their ordinary Perl numeric value.
my $mask32 = 0xFFFFFFFF;
my $from_complement = (~0) & $mask32;
is($from_complement + 0, 4294967295, 'masked complement is a 32-bit unsigned value');
is("$from_complement", '4294967295', 'masked complement stringifies as its numeric value');

my $high_bit = 0x80000000 | 0;
is($high_bit + 0, 2147483648, '32-bit high bit remains numerically exact');
is("$high_bit", '2147483648', '32-bit high bit stringifies exactly');

done_testing;
