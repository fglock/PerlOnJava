use 5.32.0;
use strict;
use warnings;
use Test::More;

# Variable assignment and modification
my $a = 15;
my $x = $a;
is($x, 15, 'variable assignment works');

$a = 12;
is($a, 12, 'variable modification works');

# if/else
my $if_test = 10;
if ($if_test > 5) {
    ok($if_test > 5, 'if branch executed correctly');
} else {
    fail('else branch should not execute');
}

# if/elsif/else
my $elsif_test = 15;
my $branch_taken;
if ($elsif_test < 10) {
    $branch_taken = 'if';
} elsif ($elsif_test > 20) {
    $branch_taken = 'elsif';
} else {
    $branch_taken = 'else';
}
is($branch_taken, 'else', 'correct elsif branch taken');

# unless
my $unless_test = 5;
unless ($unless_test > 10) {
    ok($unless_test <= 10, 'unless condition works');
}

# while loop
my $while_counter = 0;
while ($while_counter < 5) {
    $while_counter++;
}
is($while_counter, 5, 'while loop iteration count');

# until loop
my $until_counter = 0;
until ($until_counter == 5) {
    $until_counter++;
}
is($until_counter, 5, 'until loop iteration count');

# C-style for loop
my $for_sum = 0;
for (my $i = 0; $i < 5; $i++) {
    $for_sum += $i;
}
is($for_sum, 10, 'C-style for loop sum');

# for loop with continue block
my @list = (1..5);
my $list_sum = 0;
my $continue_count = 0;
for my $item (@list) {
    $list_sum += $item;
} continue {
    $continue_count++;
}
is($list_sum, 15, 'list iteration sum');
is($continue_count, 5, 'continue block execution count');

# while with continue block
my $while_sum = 0;
my $while_continue_count = 0;
while ($while_continue_count < 5) {
    $while_sum += $while_continue_count;
    $while_continue_count++;
} continue {
    $while_sum++;
}
is($while_sum, 15, 'while loop with continue block sum');

# return statement
sub test_return {
    return 42;
}
is(test_return(), 42, 'return statement value');

# nested loops
my $nested_sum = 0;
for my $i (1..3) {
    for my $j (1..3) {
        $nested_sum += $i * $j;
    }
}
is($nested_sum, 36, 'nested loops calculation');

# do-while loop
my $do_while_counter = 0;
do {
    $do_while_counter++;
} while ($do_while_counter < 5);
is($do_while_counter, 5, 'do-while loop count');

# empty body loop
my $empty_loop_counter = 0;
while ($empty_loop_counter < 5) {
    $empty_loop_counter++;
}
is($empty_loop_counter, 5, 'empty body loop count');

# complex conditional
my $complex_condition = (5 > 3 && 10 < 20) || (15 == 15 && 7 != 8);
ok($complex_condition, 'complex conditional evaluation');

# scope in loops
my $scope_test = 0;
for my $i (1..3) {
    my $local_var = $i * 2;
    $scope_test += $local_var;
}
is($scope_test, 12, 'scope in loops calculation');

done_testing();
