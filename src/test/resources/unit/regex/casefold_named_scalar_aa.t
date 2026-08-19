use strict;
use warnings;
use utf8;
use Test::More;

my $kelvin = "\N{KELVIN SIGN}";

like('k', qr/\N{KELVIN SIGN}/i,
    'named Kelvin folds with ASCII k under ordinary ignore-case');
unlike('k', qr/\N{KELVIN SIGN}/iaa,
    'named Kelvin does not cross into ASCII under aa');
unlike($kelvin, qr/k/iaa,
    'literal k does not cross into named Kelvin under aa');
like($kelvin, qr/\N{KELVIN SIGN}/iaa,
    'named Kelvin continues to match itself under aa');

like('xky', qr/^x(?i:\N{KELVIN SIGN})y$/,
    'scoped ordinary ignore-case folds named Kelvin');
unlike('xky', qr/^x(?i:\N{KELVIN SIGN})y$/aa,
    'outer aa restricts a scoped ordinary i group');
unlike('xky', qr/^x(?i:\N{KELVIN SIGN})(?-i:k)y$/,
    'scoped minus-i restores literal comparison');

my $byte_k = 'k';
utf8::downgrade($byte_k, 1);
unlike($byte_k, qr/\N{KELVIN SIGN}/aai,
    'byte subject remains outside aa named Kelvin folding');
my $unicode_k = $byte_k;
utf8::upgrade($unicode_k);
unlike($unicode_k, qr/\N{KELVIN SIGN}/aai,
    'upgraded ASCII subject remains outside aa named Kelvin folding');

done_testing;
