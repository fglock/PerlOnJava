use strict;
use warnings;
use Test::More tests => 5;

# Test 1: Basic lvalue ternary operation
my $x = 10;
my $y = 20;
($x > $y ? $x : $y) = 30;
is($x, 10, 'Test 1: $x remains unchanged');
is($y, 30, 'Test 1: $y is updated to 30');

# Test 2: lvalue ternary with array elements
my @array = (1, 2, 3);
($array[0] > $array[1] ? $array[0] : $array[1]) = 5;
is_deeply(\@array, [1, 5, 3], 'Test 2: Array element updated correctly');

# Test 3: lvalue ternary with hash values
my %hash = (a => 1, b => 2);
($hash{a} > $hash{b} ? $hash{a} : $hash{b}) = 10;
is_deeply(\%hash, {a => 1, b => 10}, 'Test 3: Hash value updated correctly');

# Test 4: lvalue ternary with entire array
my @array2 = (4, 5, 6);
my $index = 1;
($index == 1 ? @array2 : @array) = (7, 8, 9);
is_deeply(\@array2, [7, 8, 9], 'Test 4: Entire array updated correctly');

done_testing();

