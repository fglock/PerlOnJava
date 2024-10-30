use strict;
use warnings;
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
    say $global_var eq "temporarily changed" ? "ok # local scalar variable changed" : "not ok";
}
say $global_var eq "original" ? "ok # local scalar variable restored" : "not ok";

# Array case
{
    local @global_array = (4, 5, 6);
    say @global_array == 3 && $global_array[0] == 4 ? "ok # local array changed" : "not ok";
}
say @global_array == 3 && $global_array[0] == 1 ? "ok # local array restored" : "not ok";

# Hash case
{
    local %global_hash = (new_key => 'new_value');
    print exists $global_hash{new_key} ? "" : "not ";
    say "ok # local hash changed";
}
say exists $global_hash{key} ? "ok # local hash restored" : "not ok";

# Case: local with a for loop and exceptions
{
    local $global_var = "for-loop scope";
    for my $i (1..3) {
        say $global_var eq "for-loop scope" ? "ok # local variable inside for-loop" : "not ok";
    }
}
say $global_var eq "original" ? "ok # local variable restored after for-loop" : "not ok";

# Edge case: local inside a subroutine
sub modify_global_var {
    local $global_var = "inside subroutine";
    say $global_var eq "inside subroutine" ? "ok # local variable in subroutine" : "not ok";
}
modify_global_var();
say $global_var eq "original" ? "ok # local variable restored after subroutine" : "not ok";

# Special case: local with nested scopes
{
    local $global_var = "outer scope";
    {
        local $global_var = "inner scope";
        say $global_var eq "inner scope" ? "ok # inner scope local" : "not ok";
    }
    say $global_var eq "outer scope" ? "ok # outer scope local" : "not ok";
}
say $global_var eq "original" ? "ok # variable restored after nested scopes" : "not ok";

# Special case: localizing package globals
our $package_var = "package original";
{
    local $::package_var = "package temporary";
    say $::package_var eq "package temporary" ? "ok # local package variable changed" : "not ok";
}
say $::package_var eq "package original" ? "ok # local package variable restored" : "not ok";

# Special case: localizing special variables
$@ = "";
{
    local $@ = "error occurred";
    eval { die "Test error" };
    print $@ =~ "Test error" ? "" : "not ";
    say "ok # localized \$@ during eval <" . substr($@, 0, 20) . ">";
}
say $@ eq "" ? "ok # \$@ restored after eval <$@>" : "not ok";

# Test for `next` in a loop
$global_var = "original";
{
    for my $i (1..3) {
        local $global_var = "next scope";
        next if $i == 2;
        say $global_var eq "next scope" ? "ok # local variable with next" : "not ok";
    }
    say $global_var eq "original" ? "ok # local variable restored after next" : "not ok";
}

# Test for `redo` in a loop
$global_var = "original";
{
    my $count = 0;
    for my $i (1..3) {
        local $global_var = "redo scope";
        $count++;
        redo if $count == 1;  # redo the first iteration
        say $global_var eq "redo scope" ? "ok # local variable with redo" : "not ok";
    }
    say $global_var eq "original" ? "ok # local variable restored after redo" : "not ok";
}

# Test for `last` in a loop
$global_var = "original";
{
    for my $i (1..3) {
        local $global_var = "last scope";
        last if $i == 2;
        say $global_var eq "last scope" ? "ok # local variable with last" : "not ok";
    }
    say $global_var eq "original" ? "ok # local variable restored after last" : "not ok";
}

# Test for `return` in a subroutine
$global_var = "original";
sub test_return {
    local $global_var = "return scope";
    return if $global_var eq "return scope";
    say "not ok # this should not be printed";
}
test_return();
say $global_var eq "original" ? "ok # local variable restored after return" : "not ok";

# New test cases for 3-argument for loop
$global_var = "original";
{
    for (my $i = 0; $i < 3; $i++) {
        local $global_var = "3-arg for scope";
        say $global_var eq "3-arg for scope" ? "ok # local variable in 3-arg for loop" : "not ok";
    }
}
say $global_var eq "original" ? "ok # local variable restored after 3-arg for loop" : "not ok";

# Test for local array with modifications
{
    local @global_array = (7, 8, 9);
    $global_array[0] = 10;
    say @global_array == 3 && $global_array[0] == 10 ? "ok # local array modified" : "not ok";
}
say @global_array == 3 && $global_array[0] == 1 ? "ok # local array restored after modification" : "not ok";

# Test for local hash with modifications
{
    local %global_hash = (another_key => 'another_value');
    $global_hash{another_key} = 'modified_value';
    say exists $global_hash{another_key} && $global_hash{another_key} eq 'modified_value' ? "ok # local hash modified" : "not ok";
}
say exists $global_hash{key} ? "ok # local hash restored after modification" : "not ok";

# Test for local array element
{
    local $global_array[0] = 10;
    say $global_array[0] == 10 ? "ok # local array element changed" : "not ok";
}
say $global_array[0] == 1 ? "ok # local array element restored" : "not ok";

# Test for local hash element
{
    local $global_hash{key} = 'temporary_value';
    say $global_hash{key} eq 'temporary_value' ? "ok # local hash element changed" : "not ok";
}
say $global_hash{key} eq 'value' ? "ok # local hash element restored" : "not ok";

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

