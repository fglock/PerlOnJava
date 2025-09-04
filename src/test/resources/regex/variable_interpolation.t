use strict;
use warnings;
use feature 'say';
use Test::More;

###################
# Variable Interpolation in Regex Tests
# Testing the distinction between hash elements and quantifiers

# Set up test variables
my $v = "abc";
my %v = (
    '3' => 'hash_three',
    'a' => 'hash_a',
    'key' => 'hash_key',
    'xyz' => 'hash_xyz'
);
my $k = 'key';
my @arr = ('elem1', 'elem2', 'elem3');

###################
# qr// operator tests

subtest "qr// variable interpolation" => sub {
    # Test quantifier vs hash element distinction
    my $pattern1 = qr/$v{3}/;  # Should be quantifier: abc{3} = abccc
    my $pattern2 = qr/$v{a}/;  # Should be hash element: hash_a
    my $pattern3 = qr/$v{$k}/; # Should be hash element: hash_key
    my $pattern4 = qr/$v{2,3}/; # Should be quantifier: abc{2,3}

    # Test strings
    my $test1 = "abccc";
    my $test2 = "hash_a";
    my $test3 = "hash_key";
    my $test4 = "abcc";
    my $test5 = "abccc";

    ok($test1 =~ $pattern1, "qr/\$v{3}/ matches 'abccc' (quantifier)");
    ok($test2 =~ $pattern2, "qr/\$v{a}/ matches 'hash_a' (hash element)");
    ok($test3 =~ $pattern3, "qr/\$v{\$k}/ matches 'hash_key' (hash element with variable key)");
    ok($test4 =~ $pattern4, "qr/\$v{2,3}/ matches 'abcc' (quantifier range, 2 times)");
    ok($test5 =~ $pattern4, "qr/\$v{2,3}/ matches 'abccc' (quantifier range, 3 times)");

    # Test grouped quantifier
    my $pattern_grouped = qr/($v){2}/; # Should repeat entire variable
    my $test_grouped = "abcabc";
    ok($test_grouped =~ $pattern_grouped, "qr/(\$v){2}/ matches 'abcabc' (grouped quantifier)");

    # Test array interpolation - arrays are joined with $" (default is space)
    my $pattern_array = qr/@arr/; # Should join with $" (space by default)
    my $test_array = "elem1 elem2 elem3";
    ok($test_array =~ $pattern_array, "qr/\@arr/ matches joined array elements (space-separated)");

    # Test with custom separator
    {
        local $" = "_";  # Set list separator
        my $pattern_array_custom = qr/@arr/;
        my $test_array_custom = "elem1_elem2_elem3";
        ok($test_array_custom =~ $pattern_array_custom, "qr/\@arr/ matches joined array elements (custom separator)");
    }
};

###################
# m// operator tests

subtest "m// variable interpolation" => sub {
    # Test quantifier patterns
    my $string1 = "start_abccc_end";
    ok($string1 =~ m/$v{3}/, "m/\$v{3}/ matches quantifier pattern");
    is($&, 'abccc', "Match is 'abccc'");

    # Test hash element patterns
    my $string2 = "start_hash_a_end";
    ok($string2 =~ m/$v{a}/, "m/\$v{a}/ matches hash element");
    is($&, 'hash_a', "Match is 'hash_a'");

    # Test with capture groups
    my $string3 = "prefix_hash_key_suffix";
    ok($string3 =~ m/(.*)$v{$k}(.*)/, "m/\$v{\$k}/ with captures");
    is($1, 'prefix_', "First capture is 'prefix_'");
    is($2, '_suffix', "Second capture is '_suffix'");

    # Test quantifier ranges
    my $string4 = "abcc";
    my $string5 = "abccc";
    my $string6 = "abcccc";
    ok($string4 =~ m/$v{2,3}/, "m/\$v{2,3}/ matches 'abcc' (2 times)");
    ok($string5 =~ m/$v{2,3}/, "m/\$v{2,3}/ matches 'abccc' (3 times)");
    ok(!($string6 =~ m/^$v{2,3}$/), "m/^\$v{2,3}\$/ doesn't match 'abcccc' (4 times, exact match)");

    # Test numeric expression as hash key
    my $string7 = "hash_three";
    ok($string7 =~ m/$v{2+1}/, "m/\$v{2+1}/ matches hash element (expression key)");
    is($&, 'hash_three', "Match is 'hash_three'");
};

###################
# s/// operator tests (replacement)

subtest "s/// variable interpolation" => sub {
    # Test in match part (left side)
    my $text1 = "start_abccc_end";
    my $copy1 = $text1;
    $copy1 =~ s/$v{3}/REPLACED/;
    is($copy1, "start_REPLACED_end", "s/\$v{3}/.../ replaces quantifier pattern");

    my $text2 = "start_hash_a_end";
    my $copy2 = $text2;
    $copy2 =~ s/$v{a}/REPLACED/;
    is($copy2, "start_REPLACED_end", "s/\$v{a}/.../ replaces hash element");

    # Test in replacement part (right side)
    my $text3 = "start_TARGET_end";
    my $copy3 = $text3;
    $copy3 =~ s/TARGET/$v{a}/;
    is($copy3, "start_hash_a_end", "s/.../\$v{a}/ inserts hash element");

    my $copy4 = $text3;
    $copy4 =~ s/TARGET/$v{$k}/;
    is($copy4, "start_hash_key_end", "s/.../\$v{\$k}/ inserts hash element with variable key");

    # Test global replacement with quantifiers
    my $text5 = "abccc_more_abccc_end";
    my $copy5 = $text5;
    $copy5 =~ s/$v{3}/X/g;
    is($copy5, "X_more_X_end", "s/\$v{3}/.../g replaces all quantifier matches");
};

###################
# Edge cases and special scenarios

subtest "Edge cases" => sub {
    # Test undefined hash element
    delete $v{xyz};  # Make sure it's undefined
    my $pattern_undef;
    {
        no warnings 'uninitialized';
        $pattern_undef = qr/$v{xyz}/;
    }
    my $empty_string = "";
    ok($empty_string =~ $pattern_undef, "qr/\$v{xyz}/ with undefined hash element matches empty string");

    # Test different quantifier forms - fix the test strings to match the patterns
    my $test_string = "abbbbbc";  # 5 b's
    ok($test_string =~ /ab{5}c/, "Direct quantifier /ab{5}c/ works (5 b's)");

    # Test variable with quantifier on last character
    my $b = "b";
    ok($test_string =~ /a$b{5}c/, "Variable with quantifier /a\$b{5}c/ works (5 b's)");

    # Test with correct count
    my $test_4bs = "abbbbc"; # 4 b's
    ok($test_4bs =~ /ab{4}c/, "Direct quantifier /ab{4}c/ works (4 b's)");
    ok($test_4bs =~ /a$b{4}c/, "Variable with quantifier /a\$b{4}c/ works (4 b's)");

    # Test zero quantifier
    my $test_zero = "ac";
    ok($test_zero =~ /a$b{0}c/, "Zero quantifier /a\$b{0}c/ matches");

    # Test open-ended quantifier
    my $test_many = "abbbbbbbbbbc";
    ok($test_many =~ /a$b{4,}c/, "Open quantifier /a\$b{4,}c/ matches many");

    # Test array length variable
    my $array_len = $#arr;  # Should be 2
    my $test_len = "abbc";
    ok($test_len =~ /ab{$array_len}c/, "Array length in quantifier works");

    # Test complex expressions in hash keys
    my $expr_key = 1 + 2;  # Should be 3
    my $test_expr = "hash_three";
    ok($test_expr =~ /$v{$expr_key}/, "Expression in hash key /\$v{\$expr_key}/ works");

    # Test quoted hash keys
    ok($test_expr =~ /$v{'3'}/, "Quoted hash key /\$v{'3'}/ works");
    ok($test_expr =~ /$v{"3"}/, "Double-quoted hash key /\$v{\"3\"}/ works");
};

###################
# Nested and complex cases

subtest "Complex interpolation scenarios" => sub {
    # Test nested variables
    my $key_var = 'a';
    my $nested_test = "hash_a";
    ok($nested_test =~ /$v{$v{$key_var}}/, "Nested hash lookup /\$v{\$v{\$key_var}}/ works");

    # Test multiple variables in same regex
    my $multi_test = "hash_a_and_hash_key_together";
    ok($multi_test =~ /$v{a}_and_$v{$k}_together/, "Multiple hash elements in same regex");

    # Test alternation with variables
    my $alt_test1 = "hash_a";
    my $alt_test2 = "hash_key";
    ok($alt_test1 =~ /($v{a}|$v{$k})/, "Alternation with hash elements (first)");
    ok($alt_test2 =~ /($v{a}|$v{$k})/, "Alternation with hash elements (second)");

    # Test capture with variables
    my $capture_test = "prefix_hash_a_suffix";
    if ($capture_test =~ /(.*)($v{a})(.*)/) {
        is($1, 'prefix_', "First capture in complex pattern");
        is($2, 'hash_a', "Variable capture in complex pattern");
        is($3, '_suffix', "Third capture in complex pattern");
    }

    # Test case sensitivity
    my %V = ('a' => 'HASH_A');
    my $case_test = "HASH_A";
    ok($case_test =~ /$V{a}/, "Case sensitive hash variable");
    ok(!($case_test =~ /$v{a}/), "Different case doesn't match");
};

###################
# Special regex modifiers with interpolation

subtest "Regex modifiers with interpolation" => sub {
    # Test case insensitive with interpolated variables
    my $ci_test = "HASH_A";
    ok($ci_test =~ /$v{a}/i, "Case insensitive modifier with hash element");

    # Test global with quantifiers
    my $global_test = "abccc_abccc_abccc";
    my @matches = ($global_test =~ /$v{3}/g);
    is(scalar(@matches), 3, "Global modifier with quantifier finds 3 matches");

    # Test multiline
    my $multiline_test = "line1\nhash_a\nline3";
    ok($multiline_test =~ /^$v{a}$/m, "Multiline modifier with hash element");

    # Test extended regex with comments
    my $extended_test = "hash_a";
    ok($extended_test =~ /
        $v{a}  # This should match hash_a
    /x, "Extended regex with hash element interpolation");
};

done_testing();