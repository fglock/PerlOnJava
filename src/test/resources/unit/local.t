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

# Test for cross-package local with our variable (short name in sub)
{
    package CrossPkgTest;
    our $X = 0;
    sub check { return $X; }
    
    package main;
    is(CrossPkgTest::check(), 0, 'cross-package our variable before local');
    {
        local $CrossPkgTest::X = 1;
        is(CrossPkgTest::check(), 1, 'cross-package our variable inside local');
    }
    is(CrossPkgTest::check(), 0, 'cross-package our variable after local');
}

# Test for cross-package local with package switch inside sub definition
{
    our $x = 10;
    sub check_main_x { return $x; }
    
    package ZZZ;
    main::is(main::check_main_x(), 10, 'our alias persists across package change - before local');
    {
        local $main::x = 99;
        main::is(main::check_main_x(), 99, 'our alias persists across package change - inside local');
    }
    main::is(main::check_main_x(), 10, 'our alias persists across package change - after local');
    
    package main;
}

# Test for local with nested subroutine calls
{
    package NestedTest;
    our $level = 0;
    sub outer { return inner(); }
    sub inner { return $level; }
    
    package main;
    is(NestedTest::outer(), 0, 'nested sub with our - before local');
    {
        local $NestedTest::level = 5;
        is(NestedTest::outer(), 5, 'nested sub with our - inside local');
    }
    is(NestedTest::outer(), 0, 'nested sub with our - after local');
}

# Test for returning local array from subroutine
# Regression test: the returned array should contain the localized values,
# not the restored original values
{
    our @test_array = ("original1", "original2");
    
    sub return_local_array {
        local @test_array = ("local1", "local2", "local3");
        return @test_array;
    }
    
    my @result = return_local_array();
    is(scalar(@result), 3, 'returned local array has correct size');
    is($result[0], "local1", 'returned local array element 0 is localized value');
    is($result[1], "local2", 'returned local array element 1 is localized value');
    is($result[2], "local3", 'returned local array element 2 is localized value');
    is(scalar(@test_array), 2, 'original array restored after return');
    is($test_array[0], "original1", 'original array element 0 restored');
}

# Test for returning modified local array from subroutine
{
    our @modify_array = ("a", "b", "c");
    
    sub modify_and_return_local {
        local @modify_array = @modify_array;
        @modify_array = ("x");  # Modify the localized copy
        return ("result", @modify_array);
    }
    
    my @result = modify_and_return_local();
    is($result[0], "result", 'first return value is scalar');
    is($result[1], "x", 'returned local array contains modified value');
    is(scalar(@result), 2, 'return list has correct size');
    is(join(",", @modify_array), "a,b,c", 'original array restored after return');
}

# Test for returning local hash from subroutine
{
    our %test_hash = (orig_key => "orig_value");
    
    sub return_local_hash {
        local %test_hash = (local_key => "local_value");
        return %test_hash;
    }
    
    my %result = return_local_hash();
    ok(exists $result{local_key}, 'returned local hash has local key');
    is($result{local_key}, "local_value", 'returned local hash has local value');
    ok(!exists $result{orig_key}, 'returned local hash does not have original key');
    ok(exists $test_hash{orig_key}, 'original hash restored after return');
    is($test_hash{orig_key}, "orig_value", 'original hash value restored');
}

# Test: reference to localized array retains values after scope exit
{
    our @ref_test_array = ("original");
    my $ref;
    {
        local @ref_test_array = ("a", "b");
        $ref = \@ref_test_array;
        is("@{$ref}", "a b", 'reference to local array inside scope');
    }
    is("@{$ref}", "a b", 'reference to local array retains values after scope exit');
    is("@ref_test_array", "original", 'original array restored after local scope');
}

# Test: reference to localized @ARGV retains values (App::perlbrew pattern)
{
    my %opt;
    {
        local @ARGV = ("install", "perl-5.8.9");
        $opt{args} = \@ARGV;
        is("@{$opt{args}}", "install perl-5.8.9", 'ref to local @ARGV inside scope');
    }
    is("@{$opt{args}}", "install perl-5.8.9", 'ref to local @ARGV retains values after scope');
}

done_testing();

__END__

#----- TODO: localizing filehandles --------

# Special case: localizing filehandles
open my $fh, "<", "/etc/passwd" or die "Cannot open file: $!";
{
    local *FH = $fh;
    while (<FH>) {
        last if $. > 5;  # Read only first 5 lines
    }
}
say "ok # filehandle localized";
