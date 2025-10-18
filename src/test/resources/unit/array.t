use strict;
use Test::More;
use feature 'say';

# Array creation and assignment
my @array = (1, 2, 3, 4, 5);
is(scalar @array, 5, 'Array has correct length');
is($array[0], 1, 'First element is correct');
is($array[4], 5, 'Last element is correct');

# Array length
my $length = scalar @array;
is($length, 5, 'Array length is correct');

# Push operation
push @array, 6;
is($array[-1], 6, 'Push added correct element');
is(scalar @array, 6, 'Array length increased after push');

# Pop operation
my $popped = pop @array;
is($popped, 6, 'Pop returned correct element');
is(scalar @array, 5, 'Array length decreased after pop');

# Shift operation
my $shifted = shift @array;
is($shifted, 1, 'Shift returned correct element');
is(scalar @array, 4, 'Array length decreased after shift');
is($array[0], 2, 'First element is correct after shift');

# Unshift operation
unshift @array, 0;
is($array[0], 0, 'Unshift added element at beginning');
is(scalar @array, 5, 'Array length increased after unshift');

# Splice operation
splice @array, 2, 1, (10, 11);
is(scalar @array, 6, 'Array length correct after splice');
is($array[2], 10, 'Splice inserted first element correctly');
is($array[3], 11, 'Splice inserted second element correctly');

# Slice operations
{
    my @slice = @array[1..3];
    is(scalar @slice, 3, 'Slice has correct length');
    is($slice[0], 2, 'First slice element is correct');
    is($slice[2], 11, 'Last slice element is correct');
}

{
    my $array = \@array;
    my @slice = @$array[1..3];
    is(scalar @slice, 3, 'Reference slice has correct length');
    is($slice[0], 2, 'First reference slice element is correct');
    is($slice[2], 11, 'Last reference slice element is correct');
}

# Negative indexing
is($array[-1], 5, 'Negative index -1 is correct');
is($array[-2], 4, 'Negative index -2 is correct');

# Array to scalar context
my $scalar = @array;
is($scalar, 6, 'Array in scalar context gives correct length');

# Foreach loop
my $sum = 0;
foreach my $elem (@array) {
    $sum += $elem;
}
is($sum, 32, 'Foreach loop sum is correct'); # 0 + 2 + 10 + 11 + 4 + 5

# Map operation
my @doubled = map { $_ * 2 } @array;
is(scalar @doubled, 6, 'Mapped array has correct length');
is($doubled[0], 0, 'First mapped element is correct');
is($doubled[-1], 10, 'Last mapped element is correct');

# Grep operation
my @evens = grep { $_ % 2 == 0 } @array;
is(scalar @evens, 4, 'Grep filtered correct number of elements');
is($evens[0], 0, 'First even number is correct');
is($evens[-1], 4, 'Last even number is correct');

# Sort operation
my @sorted = sort { $a <=> $b } @array;
is(scalar @sorted, 6, 'Sorted array has correct length');
is($sorted[0], 0, 'First sorted element is correct');
is($sorted[-1], 11, 'Last sorted element is correct');

# Reverse operation
my @reversed = reverse @array;
is(scalar @reversed, 6, 'Reversed array has correct length');
is($reversed[0], 5, 'First reversed element is correct');
is($reversed[-1], 0, 'Last reversed element is correct');

# Array of arrays
my @matrix = ([1, 2], [3, 4], [5, 6]);
is($matrix[1][0], 3, 'Matrix element [1][0] is correct');
is($matrix[2][1], 6, 'Matrix element [2][1] is correct');

# Autovivification
$matrix[3][0] = 7;
is($matrix[3][0], 7, 'Autovivification worked correctly');

# Join operation
my $joined = join ", ", @array;
is($joined, "0, 2, 10, 11, 4, 5", 'Join produced correct string');

# Split operation
my @split = split ", ", $joined;
is(scalar @split, 6, 'Split array has correct length');
is($split[0], 0, 'First split element is correct');
is($split[-1], 5, 'Last split element is correct');

# Slice assignment
@array[1, 3, 5] = (20, 30, 40);
is($array[1], 20, 'Slice assignment element 1 is correct');
is($array[3], 30, 'Slice assignment element 3 is correct');
is($array[5], 40, 'Slice assignment element 5 is correct');

# Verify slice assignment
my @new_slice = @array[1, 3, 5];
is(scalar @new_slice, 3, 'New slice has correct length');
is($new_slice[0], 20, 'New slice element 0 is correct');
is($new_slice[2], 40, 'New slice element 2 is correct');

done_testing();