use strict;
use warnings;
use Test::More;

my @values;
$#values = 7;
for (($#values) x 2) {
    $_ *= 2;
}

is($#values, 28, 'list repetition preserves an array-last-index lvalue');

done_testing;
