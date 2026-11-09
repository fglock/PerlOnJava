use strict;
use warnings;
use FindBin;
use lib "$FindBin::Bin/fixtures";
use Test::More;
use Math::BigInt;
use List::Util::PP qw(sum product);

my $sum_input = Math::BigInt->new('36893488147419103232');
my $sum_result = sum(42, $sum_input);
is($sum_result->bstr, '36893488147419103274',
    'sum preserves BigInt precision with a built-in integer');
is($sum_input->bstr, '36893488147419103232',
    'sum does not mutate its BigInt input');

my $product_input = Math::BigInt->new('36893488147419103232');
my $product_result = product(42, $product_input);
is($product_result->bstr, '1549526502191602335744',
    'product preserves BigInt precision with a built-in integer');
is($product_input->bstr, '36893488147419103232',
    'product does not mutate its BigInt input');

done_testing();
