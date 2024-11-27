use feature 'say';
use strict;

###################
# Perl Hash Operations Tests

# Hash creation and assignment
my %hash = (key1 => 'value1', key2 => 'value2');
print "not " if $hash{key1} ne 'value1' or $hash{key2} ne 'value2';
say "ok # Hash creation and assignment";

# Hashref
my $hashref = \%hash;
print "not " if $hashref->{key1} ne 'value1' or $hashref->{key2} ne 'value2';
say "ok # Hashref dereference";

# Exists
print "not " if !exists $hash{key1} or exists $hash{nonexistent};
say "ok # Exists operation";

# Delete
delete $hash{key1};
print "not " if exists $hash{key1} or !exists $hash{key2};
say "ok # Delete operation";

# Assign
$hash{key3} = 'value3';
print "not " if $hash{key3} ne 'value3';
say "ok # Assign operation";

# Count
my $count = keys %hash;
print "not " if $count != 2;
say "ok # Count operation";

# Iterate
my $iterated_count = 0;
for my $key (keys %hash) {
    $iterated_count++;
}
print "not " if $iterated_count != $count;
say "ok # Iterate operation";

# Slice
{
my @slice = @hash{'key2', 'key3'};
print "not " if @slice != 2 or $slice[0] ne 'value2' or $slice[1] ne 'value3';
say "ok # Slice operation";
}

{
my $hash = \%hash;
my @slice = @$hash{'key2', 'key3'};
print "not " if @slice != 2 or $slice[0] ne 'value2' or $slice[1] ne 'value3';
say "ok # Slice operation";
}

# Slice delete
delete @hash{'key2', 'key3'};
print "not " if exists $hash{key2} or exists $hash{key3};
say "ok # Slice delete operation";

# Autovivification
$hash{outer}{inner} = 'nested';
print "not " if $hash{outer}{inner} ne 'nested';
say "ok # Autovivification";

# Complex nested structure
my %nested = (
    a => { b => { c => 1 } },
    x => [ { y => 2 }, { z => 3 } ]
);
print "not " if $nested{a}{b}{c} != 1 or $nested{x}[0]{y} != 2 or $nested{x}[1]{z} != 3;
say "ok # Complex nested structure";

# Hash of arrays
my %hash_of_arrays = (
    fruits => ['apple', 'banana', 'cherry'],
    colors => ['red', 'green', 'blue']
);
print "not " if $hash_of_arrays{fruits}[1] ne 'banana' or $hash_of_arrays{colors}[2] ne 'blue';
say "ok # Hash of arrays";

# Array of hashes
my @array_of_hashes = (
    { name => 'Alice', age => 30 },
    { name => 'Bob', age => 25 }
);
print "not " if $array_of_hashes[0]{name} ne 'Alice' or $array_of_hashes[1]{age} != 25;
say "ok # Array of hashes";

# Slice assignment
@hash{'key4', 'key5'} = ('value4', 'value5');
print "not " if $hash{key4} ne 'value4' or $hash{key5} ne 'value5';
say "ok # Slice assignment";

# Verify slice assignment
my @new_slice = @hash{'key4', 'key5'};
print "not " if @new_slice != 2 or $new_slice[0] ne 'value4' or $new_slice[1] ne 'value5';
say "ok # Verify slice assignment";
