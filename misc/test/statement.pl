use 5.32.0;
use strict;
use feature 'say';
use feature 'isa';

# Test variable assignment and modification
my $a = 15;
my $x = $a;
print "not " if $x != 15; say "ok # \$x is 15";

$a = 12;
print "not " if $a != 12; say "ok # \$a is 12";

# Test if/else
my $if_test = 10;
if ($if_test > 5) {
    print "not " if $if_test <= 5; say "ok # if statement works";
} else {
    print "not "; say "ok # if statement works";
}

# Test if/elsif/else
my $elsif_test = 15;
if ($elsif_test < 10) {
    print "not "; say "ok # elsif statement works";
} elsif ($elsif_test > 20) {
    print "not "; say "ok # elsif statement works";
} else {
    print "not " if $elsif_test < 10 || $elsif_test > 20; say "ok # elsif statement works";
}

# Test unless
my $unless_test = 5;
unless ($unless_test > 10) {
    print "not " if $unless_test > 10; say "ok # unless statement works";
}

# Test while loop
my $while_counter = 0;
while ($while_counter < 5) {
    $while_counter++;
}
print "not " if $while_counter != 5; say "ok # while loop works";

# Test until loop
my $until_counter = 0;
until ($until_counter == 5) {
    $until_counter++;
}
print "not " if $until_counter != 5; say "ok # until loop works";

# Test for loop (C-style, 3-argument)
my $for_sum = 0;
for (my $i = 0; $i < 5; $i++) {
    $for_sum += $i;
}
print "not " if $for_sum != 10; say "ok # C-style for loop works";

# Test for loop (list iteration) with continue block
my @list = (1, 2, 3, 4, 5);
my $list_sum = 0;
my $continue_count = 0;
for my $item (@list) {
    $list_sum += $item;
} continue {
    $continue_count++;
}
print "not " if $list_sum != 15; say "ok # list iteration for loop works";
print "not " if $continue_count != 5; say "ok # continue block in list iteration for loop works";

# Test while loop with continue block
my $while_sum = 0;
my $while_continue_count = 0;
while ($while_continue_count < 5) {
    $while_sum += $while_continue_count;
    $while_continue_count++;
} continue {
    $while_sum++;
}
print "not " if $while_sum != 15; say "ok # while loop with continue block works";

# Test return statement
sub test_return {
    return 42;
}
my $return_value = test_return();
print "not " if $return_value != 42; say "ok # return statement works";

# Test nested loops
my $nested_sum = 0;
for my $i (1..3) {
    for my $j (1..3) {
        $nested_sum += $i * $j;
    }
}
print "not " if $nested_sum != 36; say "ok # nested loops work";

# Test do-while loop
my $do_while_counter = 0;
do {
    $do_while_counter++;
} while ($do_while_counter < 5);
print "not " if $do_while_counter != 5; say "ok # do-while loop works";

# Test loop with empty body
my $empty_loop_counter = 0;
while ($empty_loop_counter < 5) {
    $empty_loop_counter++;
    # Empty body
}
print "not " if $empty_loop_counter != 5; say "ok # loop with empty body works";

# Test conditional with complex expression
my $complex_condition = (5 > 3 && 10 < 20) || (15 == 15 && 7 != 8);
if ($complex_condition) {
    print "not " if !$complex_condition; say "ok # conditional with complex expression works";
} else {
    print "not "; say "ok # conditional with complex expression works";
}

# Test scope in loops
my $scope_test = 0;
for my $i (1..3) {
    my $local_var = $i * 2;
    $scope_test += $local_var;
}
print "not " if $scope_test != 12; say "ok # scope in loops works";

