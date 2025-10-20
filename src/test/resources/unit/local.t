use strict;
use warnings;
use Test::More;
use feature 'say';

#######################
# Tests for Perl `local` operator

# Save original values for later comparison
our $global_var = "original";
our @global_array = (1, 2, 3);
our %global_hash = (key => 'value');

# Simple scalar case
{
    local $global_var = "temporarily changed";
    is($global_var, "temporarily changed", 'local scalar variable changed');
}
is($global_var, "original", 'local scalar variable restored');

# Array case
{
    local @global_array = (4, 5, 6);
    ok(@global_array == 3 && $global_array[0] == 4, 'local array changed');
}
ok(@global_array == 3 && $global_array[0] == 1, 'local array restored');

# Hash case
{
    local %global_hash = (new_key => 'new_value');
    ok(exists $global_hash{new_key}, 'local hash changed');
}
ok(exists $global_hash{key}, 'local hash restored');

# Case: local with a for loop and exceptions
{
    local $global_var = "for-loop scope";
    for my $i (1..3) {
        is($global_var, "for-loop scope", 'local variable inside for-loop');
    }
}
is($global_var, "original", 'local variable restored after for-loop');

# Edge case: local inside a subroutine
sub modify_global_var {
    local $global_var = "inside subroutine";
    is($global_var, "inside subroutine", 'local variable in subroutine');
}
modify_global_var();
is($global_var, "original", 'local variable restored after subroutine');

# Special case: local with nested scopes
{
    local $global_var = "outer scope";
    {
        local $global_var = "inner scope";
        is($global_var, "inner scope", 'inner scope local');
    }
    is($global_var, "outer scope", 'outer scope local');
}
is($global_var, "original", 'variable restored after nested scopes');

# Special case: localizing package globals
our $package_var = "package original";
{
    local $::package_var = "package temporary";
    is($::package_var, "package temporary", 'local package variable changed');
}
is($::package_var, "package original", 'local package variable restored');

# Special case: localizing special variables
$@ = "";
{
    local $@ = "error occurred";
    eval { die "Test error" };
    like($@, qr/Test error/, 'localized $@ during eval');
}
is($@, "", '$@ restored after eval');

# Test for `next` in a loop
$global_var = "original";
{
    for my $i (1..3) {
        local $global_var = "next scope";
        next if $i == 2;
        is($global_var, "next scope", 'local variable with next');
    }
    is($global_var, "original", 'local variable restored after next');
}

# Test for `redo` in a loop
$global_var = "original";
{
    my $count = 0;
    for my $i (1..3) {
        local $global_var = "redo scope";
        $count++;
        redo if $count == 1;  # redo the first iteration
        is($global_var, "redo scope", 'local variable with redo');
    }
    is($global_var, "original", 'local variable restored after redo');
}

# Test for `last` in a loop
$global_var = "original";
{
    for my $i (1..3) {
        local $global_var = "last scope";
        last if $i == 2;
        is($global_var, "last scope", 'local variable with last');
    }
    is($global_var, "original", 'local variable restored after last');
}

# Test for `return` in a subroutine
$global_var = "original";
sub test_return {
    local $global_var = "return scope";
    return if $global_var eq "return scope";
    fail('this should not be printed');
}
test_return();
is($global_var, "original", 'local variable restored after return');

# New test cases for 3-argument for loop
$global_var = "original";
{
    for (my $i = 0; $i < 3; $i++) {
        local $global_var = "3-arg for scope";
        is($global_var, "3-arg for scope", 'local variable in 3-arg for loop');
    }
}
is($global_var, "original", 'local variable restored after 3-arg for loop');

# Test for local array with modifications
{
    local @global_array = (7, 8, 9);
    $global_array[0] = 10;
    ok(@global_array == 3 && $global_array[0] == 10, 'local array modified');
}
ok(@global_array == 3 && $global_array[0] == 1, 'local array restored after modification');

# Test for local hash with modifications
{
    local %global_hash = (another_key => 'another_value');
    $global_hash{another_key} = 'modified_value';
    ok(exists $global_hash{another_key} && $global_hash{another_key} eq 'modified_value', 'local hash modified');
}
ok(exists $global_hash{key}, 'local hash restored after modification');

# Test for local array element
{
    local $global_array[0] = 10;
    is($global_array[0], 10, 'local array element changed');
}
is($global_array[0], 1, 'local array element restored');

# Test for local hash element
{
    local $global_hash{key} = 'temporary_value';
    is($global_hash{key}, 'temporary_value', 'local hash element changed');
}
is($global_hash{key}, 'value', 'local hash element restored');

# Test for new local hash element
{
    local $global_hash{key_new} = 'temporary_value';
    is($global_hash{key_new}, 'temporary_value', 'local hash element changed');
}
ok(!defined($global_hash{key_new}), 'local hash element restored');

# Test for new local array element
{
    local $global_array[10] = 'temporary_value';
    is($global_array[10], 'temporary_value', 'local array element changed');
}
ok(scalar(@global_array) < 10, 'local array element restored, array size ' . scalar(@global_array));

done_testing();

__END__

#----- TODO --------

# Special case: localizing filehandles
open my $fh, "<", "/etc/passwd" or die "Cannot open file: $!";
{
    local *FH = $fh;
    while (<FH>) {
        last if $. > 5;  # Read only first 5 lines
    }
}
say "ok # filehandle localized";
