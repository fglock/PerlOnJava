use strict;
use Test::More;
use feature 'say';

# Hash creation and assignment
my %hash = (key1 => 'value1', key2 => 'value2');
is($hash{key1}, 'value1', 'Hash key1 has correct value');
is($hash{key2}, 'value2', 'Hash key2 has correct value');

# Hashref
my $hashref = \%hash;
is($hashref->{key1}, 'value1', 'Hashref key1 dereference');
is($hashref->{key2}, 'value2', 'Hashref key2 dereference');

# Exists
ok(exists $hash{key1}, 'key1 exists');
ok(!exists $hash{nonexistent}, 'nonexistent key does not exist');

# Delete
delete $hash{key1};
ok(!exists $hash{key1}, 'key1 deleted');
ok(exists $hash{key2}, 'key2 still exists');

# Assign
$hash{key3} = 'value3';
is($hash{key3}, 'value3', 'New key assignment');

# Count
is(scalar(keys %hash), 2, 'Hash has correct number of keys');

# Iterate
my $iterated_count = 0;
$iterated_count++ for keys %hash;
is($iterated_count, scalar(keys %hash), 'Iteration count matches key count');

# Slice operations
{
    my @slice = @hash{'key2', 'key3'};
    is_deeply(\@slice, ['value2', 'value3'], 'Hash slice retrieval');
}

{
    my $hash = \%hash;
    my @slice = @$hash{'key2', 'key3'};
    is_deeply(\@slice, ['value2', 'value3'], 'Hashref slice retrieval');
}

# Slice delete
delete @hash{'key2', 'key3'};
ok(!exists $hash{key2} && !exists $hash{key3}, 'Slice delete successful');

# Autovivification
$hash{outer}{inner} = 'nested';
is($hash{outer}{inner}, 'nested', 'Autovivification works');

# Complex nested structure
my %nested = (
    a => { b => { c => 1 } },
    x => [ { y => 2 }, { z => 3 } ]
);
is($nested{a}{b}{c}, 1, 'Nested hash access');
is($nested{x}[0]{y}, 2, 'Array in hash access');
is($nested{x}[1]{z}, 3, 'Complex nested structure access');

# Hash of arrays
my %hash_of_arrays = (
    fruits => ['apple', 'banana', 'cherry'],
    colors => ['red', 'green', 'blue']
);
is($hash_of_arrays{fruits}[1], 'banana', 'Hash of arrays - fruits');
is($hash_of_arrays{colors}[2], 'blue', 'Hash of arrays - colors');

# Array of hashes
my @array_of_hashes = (
    { name => 'Alice', age => 30 },
    { name => 'Bob', age => 25 }
);
is($array_of_hashes[0]{name}, 'Alice', 'Array of hashes - name check');
is($array_of_hashes[1]{age}, 25, 'Array of hashes - age check');

# Slice assignment
@hash{'key4', 'key5'} = ('value4', 'value5');
is_deeply([@hash{'key4', 'key5'}], ['value4', 'value5'], 'Slice assignment and retrieval');

done_testing();
