use strict;
use Test::More;
use feature 'say';

# Test array for operations
my @array = (10, 20, 30, 40, 50);

# Test exists() on array elements
ok(exists $array[0], 'exists() returns true for element 0');
ok(exists $array[4], 'exists() returns true for element 4');
ok(!exists $array[5], 'exists() returns false for element beyond array');
ok(!exists $array[10], 'exists() returns false for far beyond array');

# Test exists() with negative indices
ok(exists $array[-1], 'exists() returns true for -1 (last element)');
ok(exists $array[-5], 'exists() returns true for -5 (first element)');
ok(!exists $array[-6], 'exists() returns false for -6 (before array)');

# Test delete() on array elements
my $deleted = delete $array[2];
is($deleted, 30, 'delete() returns the deleted value');
ok(!exists $array[2], 'exists() returns false after delete');
ok(!defined $array[2], 'deleted element is undefined');
is(scalar @array, 5, 'Array length unchanged after delete');

# Test delete() on multiple elements
delete $array[0];
delete $array[4];
ok(!exists $array[0], 'exists() returns false for deleted element 0');
ok(!exists $array[4], 'exists() returns false for deleted element 4');
ok(exists $array[1], 'exists() returns true for undeleted element 1');
is($array[1], 20, 'Undeleted element 1 has correct value');
is($array[3], 40, 'Undeleted element 3 has correct value');

# Test delete() on non-existent element
my $del_nonexist = delete $array[10];
ok(!defined $del_nonexist, 'delete() on non-existent element returns undef');

# Test $#array (last index)
@array = (1, 2, 3, 4, 5);
is($#array, 4, '$#array returns correct last index');

# Set $#array to shrink array
$#array = 2;
is(scalar @array, 3, 'Setting $#array shrinks array correctly');
is($#array, 2, '$#array updated correctly after shrinking');
ok(!exists $array[3], 'Element 3 no longer exists after shrinking');
ok(!exists $array[4], 'Element 4 no longer exists after shrinking');

# Set $#array to expand array
$#array = 6;
is(scalar @array, 7, 'Setting $#array expands array correctly');
is($#array, 6, '$#array updated correctly after expanding');
# Note: expanded elements don't "exist" even though they're in bounds
ok(!exists $array[3], 'Element 3 does not exist after expanding');
ok(!exists $array[6], 'Element 6 does not exist after expanding');
ok(!defined $array[3], 'New element 3 is undefined');
ok(!defined $array[6], 'New element 6 is undefined');

# But we can access them
is($array[3], undef, 'Can access element 3 as undef');
is($array[6], undef, 'Can access element 6 as undef');

# And if we assign to them, they will exist
$array[3] = 'exists now';
ok(exists $array[3], 'Element 3 exists after assignment');
is($array[3], 'exists now', 'Element 3 has correct value after assignment');

# Set $#array to -1 (empty array)
$#array = -1;
is(scalar @array, 0, 'Setting $#array to -1 empties array');
is($#array, -1, '$#array is -1 for empty array');
ok(!exists $array[0], 'No element 0 in empty array');

# Test with array reference
my $aref = [100, 200, 300, 400];
ok(exists $aref->[1], 'exists() works on array reference');
is(delete $aref->[1], 200, 'delete() works on array reference');
ok(!exists $aref->[1], 'exists() returns false after delete on reference');

$#{$aref} = 1;
is(scalar @{$aref}, 2, 'Setting $#{$aref} works on array reference');
is($aref->[0], 100, 'Element 0 preserved after shrinking reference');
ok(!exists $aref->[2], 'Element 2 removed after shrinking reference');

# Test delete in list context
@array = (10, 20, 30, 40, 50);
my @deleted = delete @array[1, 3];
is(scalar @deleted, 2, 'delete in list context returns correct number of elements');
is($deleted[0], 20, 'First deleted element is correct');
is($deleted[1], 40, 'Second deleted element is correct');
ok(!exists $array[1], 'Element 1 deleted in list context');
ok(!exists $array[3], 'Element 3 deleted in list context');

# Test exists with autovivification
my @new_array;
$new_array[5] = 'test';
ok(exists $new_array[5], 'exists() returns true for assigned element');
ok(!exists $new_array[4], 'exists() returns false for autovivified undef');
ok(!exists $new_array[0], 'exists() returns false for autovivified undef at start');
# But the array has the right size
is(scalar @new_array, 6, 'Array has correct size after autovivification');

# Test combination of operations
@array = (1..10);
$#array = 4;  # Keep only first 5 elements
delete $array[2];  # Delete middle element
ok(exists $array[0], 'Element 0 exists after operations');
ok(exists $array[1], 'Element 1 exists after operations');
ok(!exists $array[2], 'Element 2 deleted correctly');
ok(exists $array[3], 'Element 3 exists after operations');
ok(exists $array[4], 'Element 4 exists after operations');
ok(!exists $array[5], 'Element 5 removed by shrinking');
is($array[0], 1, 'Element 0 has correct value');
is($array[1], 2, 'Element 1 has correct value');
is($array[3], 4, 'Element 3 has correct value');
is($array[4], 5, 'Element 4 has correct value');

# Additional test: exists() behavior with holes
@array = (1, 2, 3);
$array[5] = 6;  # Create hole
ok(exists $array[0], 'Element 0 exists');
ok(exists $array[1], 'Element 1 exists');
ok(exists $array[2], 'Element 2 exists');
ok(!exists $array[3], 'Element 3 (hole) does not exist');
ok(!exists $array[4], 'Element 4 (hole) does not exist');
ok(exists $array[5], 'Element 5 exists');
is(scalar @array, 6, 'Array has correct size with holes');
is($#array, 5, '$#array is correct with holes');

done_testing();
