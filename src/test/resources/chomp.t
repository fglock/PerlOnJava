use feature 'say';
use strict;
use warnings;

###################
# Perl chomp Function Tests

# Test 1: Basic newline removal
my $str1 = "Hello\n";
my $chomped1 = chomp($str1);
print "not " if $str1 ne "Hello" || $chomped1 != 1;
say "ok # Basic newline removal";

# Test 2: No newline to remove
my $str2 = "Hello";
my $chomped2 = chomp($str2);
print "not " if $str2 ne "Hello" || $chomped2 != 0;
say "ok # No newline to remove";

# Test 3: Multiple newlines (should only remove one)
my $str3 = "Hello\n\n";
my $chomped3 = chomp($str3);
print "not " if $str3 ne "Hello\n" || $chomped3 != 1;
say "ok # Multiple newlines";

# Test 4: Empty string
my $str4 = "";
my $chomped4 = chomp($str4);
print "not " if $str4 ne "" || $chomped4 != 0;
say "ok # Empty string";

# Test 5: Chomp with custom record separator
my $old_sep = $/;
{
    $/ = "END";
    my $str5 = "HelloEND";
    my $chomped5 = chomp($str5);
    print "not " if $str5 ne "Hello" || $chomped5 != 3;
    say "ok # Custom record separator";
}

# Test 6: Chomp in paragraph mode
{
    $/ = "";
    my $str6 = "Hello\n\n\n";
    my $chomped6 = chomp($str6);
    print "not " if $str6 ne "Hello" || $chomped6 != 3;
    say "ok # Paragraph mode";
}

# Test 7: Chomp in slurp mode (should not remove anything)
{
    $/ = undef;
    my $str7 = "Hello\n";
    my $chomped7 = chomp($str7);
    print "not " if $str7 ne "Hello\n" || $chomped7 != 0;
    say "ok # Slurp mode";
}

# Test 8: Chomp with multi-character separator
{
    $/ = "\r\n";
    my $str8 = "Hello\r\n";
    my $chomped8 = chomp($str8);
    print "not " if $str8 ne "Hello" || $chomped8 != 2;
    say "ok # Multi-character separator";
}
$/ = $old_sep;

# Test 9: Chomp on an array
my @arr = ("Hello\n", "World\n", "Test");
my $chomped9 = chomp(@arr);
print "not " if $arr[0] ne "Hello" || $arr[1] ne "World" || $arr[2] ne "Test" || $chomped9 != 2;
say "ok # Chomp on array";

## # Test 10: Chomp on hash values
## my %hash = (key1 => "Value1\n", key2 => "Value2\n", key3 => "Value3");
## my $chomped10 = 0;
## for my $value (values %hash) {
##     $chomped10 += chomp($value);
## }
## print "not " if $hash{key1} ne "Value1" || $hash{key2} ne "Value2" || $hash{key3} ne "Value3" || $chomped10 != 2;
## say "ok # Chomp on hash values";

# Test 11: Chomp on an assignment
my $chomped11 = chomp(my $assigned = "Assigned\n");
print "not " if $assigned ne "Assigned" || $chomped11 != 1;
say "ok # Chomp on assignment";

# Test 12: Chomp with no argument (should use $_)
$old_sep = $/;
{
    $_ = "Default\n";
    my $chomped12 = chomp;
    print "not " if $_ ne "Default" || $chomped12 != 1;
    say "ok # Chomp with no argument";
}
$/ = $old_sep;


