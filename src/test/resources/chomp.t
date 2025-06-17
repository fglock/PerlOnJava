use strict;
use warnings;
use Test::More;
use feature 'say';

# Basic newline removal
my $str1 = "Hello\n";
my $chomped1 = chomp($str1);
is($str1, "Hello", 'Basic newline removal - string');
is($chomped1, 1, 'Basic newline removal - return value');

# No newline to remove
my $str2 = "Hello";
my $chomped2 = chomp($str2);
is($str2, "Hello", 'No newline to remove - string');
is($chomped2, 0, 'No newline to remove - return value');

# Multiple newlines (should only remove one)
my $str3 = "Hello\n\n";
my $chomped3 = chomp($str3);
is($str3, "Hello\n", 'Multiple newlines - string');
is($chomped3, 1, 'Multiple newlines - return value');

# Empty string
my $str4 = "";
my $chomped4 = chomp($str4);
is($str4, "", 'Empty string - string');
is($chomped4, 0, 'Empty string - return value');

# Custom record separator
my $old_sep = $/;
{
    local $/ = "END";
    my $str5 = "HelloEND";
    my $chomped5 = chomp($str5);
    is($str5, "Hello", 'Custom separator - string');
    is($chomped5, 3, 'Custom separator - return value');
}

# Paragraph mode
{
    local $/ = "";
    my $str6 = "Hello\n\n\n";
    my $chomped6 = chomp($str6);
    is($str6, "Hello", 'Paragraph mode - string');
    is($chomped6, 3, 'Paragraph mode - return value');
}

# Slurp mode
{
    local $/ = undef;
    my $str7 = "Hello\n";
    my $chomped7 = chomp($str7);
    is($str7, "Hello\n", 'Slurp mode - string');
    is($chomped7, 0, 'Slurp mode - return value');
}

# Multi-character separator
{
    local $/ = "\r\n";
    my $str8 = "Hello\r\n";
    my $chomped8 = chomp($str8);
    is($str8, "Hello", 'Multi-character separator - string');
    is($chomped8, 2, 'Multi-character separator - return value');
}

# Chomp on array
my @arr = ("Hello\n", "World\n", "Test");
my $chomped9 = chomp(@arr);
is_deeply(\@arr, ["Hello", "World", "Test"], 'Array chomp - values');
is($chomped9, 2, 'Array chomp - return value');

# Chomp on assignment
my $chomped11 = chomp(my $assigned = "Assigned\n");
is($assigned, "Assigned", 'Assignment chomp - string');
is($chomped11, 1, 'Assignment chomp - return value');

# Chomp with no argument
{
    $_ = "Default\n";
    my $chomped12 = chomp;
    is($_, "Default", 'Default variable chomp - string');
    is($chomped12, 1, 'Default variable chomp - return value');
}

done_testing();
