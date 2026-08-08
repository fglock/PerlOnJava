use strict;
use warnings;

use Config;
use Test::More tests => 16;

my $bits = $Config{ivsize} * 8;
my ($min_sint, $max_sint);
{
    use integer;
    $min_sint = 1 << ($bits - 1);
    $max_sint = ~$min_sint;

    is($max_sint + 1, $min_sint, 'integer addition wraps at native width');
    is($min_sint - 1, $max_sint, 'integer subtraction wraps at native width');
    is($max_sint * 2, -2, 'integer multiplication wraps at native width');
    is(-$min_sint, $min_sint, 'integer negation wraps at native width');

    is(0 | $min_sint, $min_sint, 'integer bitwise or preserves signed tag');
    is(0 ^ $min_sint, $min_sint, 'integer bitwise xor preserves signed tag');
    is($min_sint & $min_sint, $min_sint, 'integer bitwise and preserves signed tag');

    my $compound = $max_sint;
    $compound += 1;
    is($compound, $min_sint, 'integer compound addition wraps');
    $compound -= 1;
    is($compound, $max_sint, 'integer compound subtraction wraps');
    $compound *= 2;
    is($compound, -2, 'integer compound multiplication wraps');

    my $shifted = 1;
    $shifted <<= $bits - 1;
    is($shifted, $min_sint, 'integer compound left shift preserves signed tag');
}

my $max_uint = ~0;
ok($max_uint > 0, 'ordinary numeric bitwise not produces unsigned maximum');
is(0 | $max_uint, $max_uint, 'ordinary bitwise or preserves unsigned bits');
is(0 ^ $max_uint, $max_uint, 'ordinary bitwise xor preserves unsigned bits');
is($max_uint & $max_uint, $max_uint, 'ordinary bitwise and preserves unsigned bits');

{
    use integer;
    is(0 | $max_uint, -1, 'integer bitwise or reinterprets unsigned bits as signed');
}
