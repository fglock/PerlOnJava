use strict;
use warnings;
use Test::More;

my @expected_squares = qw(
    281474976710656
    1125899906842624
    4503599627370496
    18014398509481984
    72057594037927936
    288230376151711744
    1152921504606846976
    4611686018427387904
);
my @squares = map { $_ * $_ } map { 2 ** $_ } 24 .. 31;
is_deeply([map { "$_" } @squares], \@expected_squares,
    'lossless integral NV operands retain exact IV products');

for my $index (0 .. $#squares) {
    my $expected = $expected_squares[$index];
    ok("$squares[$index]" =~ /\A(\d+)\z/,
        "square $expected matches an exact decimal pattern");
    is($1, $expected, "square $expected is captured completely");
}

my $uv_product = (2 ** 32) * ((2 ** 32) - 1);
is("$uv_product", '18446744069414584320',
    'lossless integral NV operands can produce a UV product');

my $iv_min_product = -(2 ** 31) * (2 ** 32);
is("$iv_min_product", '-9223372036854775808',
    'lossless integral NV operands preserve the IV minimum');

like("" . ((2 ** 32) * (2 ** 32)), qr/[eE+]/,
    'a product beyond UV remains floating');
like("" . ((2 ** 63) * 1), qr/[eE+]/,
    'an NV outside signed IV range is not coerced to UV');
is(4294967296.5 * 2, 8589934593,
    'a fractional operand remains on the NV multiplication path');
like("" . (1.5 * 1_000_000_000_000_000), qr/[eE+]/,
    'a large fractional product retains floating stringification');

{
    no warnings;
    my $product = (2 ** 31) * (2 ** 31);
    is("$product", '4611686018427387904',
        'warning-free arithmetic preserves exact integral multiplication');
}

{
    no overloading;
    my $product = (2 ** 31) * (2 ** 31);
    is("$product", '4611686018427387904',
        'no-overload arithmetic preserves exact integral multiplication');
}

done_testing;
