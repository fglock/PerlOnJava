use feature 'say';
use strict;

###################
# Perl Array Operations Tests

# Array creation and assignment
my @array = (1, 2, 3, 4, 5);
print "not " if @array != 5 or $array[0] != 1 or $array[4] != 5;
say "ok # Array creation and assignment";

# Array length
my $length = scalar @array;
print "not " if $length != 5;
say "ok # Array length";

# Push operation
push @array, 6;
print "not " if $array[-1] != 6 or @array != 6;
say "ok # Push operation";

# Pop operation
my $popped = pop @array;
print "not " if $popped != 6 or @array != 5;
say "ok # Pop operation";

# Shift operation
my $shifted = shift @array;
print "not " if $shifted != 1 or @array != 4 or $array[0] != 2;
say "ok # Shift operation";

# Unshift operation
unshift @array, 0;
print "not " if $array[0] != 0 or @array != 5;
say "ok # Unshift operation";

# Splice operation
splice @array, 2, 1, (10, 11);
print "not " if @array != 6 or $array[2] != 10 or $array[3] != 11;
say "ok # Splice operation";

# Slice operation
{
my @slice = @array[1..3];
print "not " if @slice != 3 or $slice[0] != 2 or $slice[2] != 11;
say "ok # Slice operation";
}

{
my $array = \@array;
my @slice = @$array[1..3];
print "not " if @slice != 3 or $slice[0] != 2 or $slice[2] != 11;
say "ok # Slice operation";
}

# Negative indexing
print "not " if $array[-1] != 5 or $array[-2] != 4;
say "ok # Negative indexing";

# Array to scalar context
my $scalar = @array;
print "not " if $scalar != 6;
say "ok # Array to scalar context";

# Foreach loop
my $sum = 0;
foreach my $elem (@array) {
    $sum += $elem;
}
print "not " if $sum != 32;  # 0 + 2 + 10 + 11 + 4 + 5
say "ok # Foreach loop";

# Map operation
my @doubled = map { $_ * 2 } @array;
print "not " if @doubled != 6 or $doubled[0] != 0 or $doubled[-1] != 10;
say "ok # Map operation";

# Grep operation
my @evens = grep { $_ % 2 == 0 } @array;
print "not " if @evens != 4 or $evens[0] != 0 or $evens[-1] != 4;
say "ok # Grep operation: <@evens>";

# Sort operation
my @sorted = sort { $a <=> $b } @array;
print "not " if @sorted != 6 or $sorted[0] != 0 or $sorted[-1] != 11;
say "ok # Sort operation";

# Reverse operation
my @reversed = reverse @array;
print "not " if @reversed != 6 or $reversed[0] != 5 or $reversed[-1] != 0;
say "ok # Reverse operation";

# Array of arrays
my @matrix = ([1, 2], [3, 4], [5, 6]);
print "not " if $matrix[1][0] != 3 or $matrix[2][1] != 6;
say "ok # Array of arrays";

# Autovivification
$matrix[3][0] = 7;
print "not " if $matrix[3][0] != 7;
say "ok # Autovivification in arrays";

# Join operation
my $joined = join ", ", @array;
print "not " if $joined ne "0, 2, 10, 11, 4, 5";
say "ok # Join operation";

# Split operation
my @split = split ", ", $joined;
print "not " if @split != 6 or $split[0] != 0 or $split[-1] != 5;
say "ok # Split operation";

# Slice assignment
@array[1, 3, 5] = (20, 30, 40);
print "not " if $array[1] != 20 or $array[3] != 30 or $array[5] != 40;
say "ok # Slice assignment";

# Verify slice assignment
my @new_slice = @array[1, 3, 5];
print "not " if @new_slice != 3 or $new_slice[0] != 20 or $new_slice[2] != 40;
say "ok # Verify slice assignment";
