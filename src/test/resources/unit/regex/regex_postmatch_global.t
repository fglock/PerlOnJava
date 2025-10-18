use strict;
use feature 'say';
use Test::More;

###################
# Test $' (postmatch) variable behavior after global matches
# This test covers the specific issue where $' was returning UNDEF
# after global matches, and the interaction between global and regular matches.

subtest "Basic postmatch variable tests" => sub {
    my $string = "Hello World";
    
    # Simple match to establish baseline
    $string =~ /World/;
    is($', '', "Simple match: \$' should be empty (no text after 'World')");
    
    # Match with text after
    $string =~ /Hello/;
    is($', ' World', "Match with postmatch: \$' should be ' World'");
};

subtest "Global match postmatch behavior" => sub {
    my $string = "ooaoaoao";
    my $rx = qr/o/;
    
    # Perform global match in list context
    my @matches = ($string =~ /$rx/g);
    is(scalar(@matches), 5, "Global match should find 5 'o' characters");
    
    # Check $' after global match - should be empty (no text after last match)
    is($', '', "After global match: \$' should be empty string");
    
    # Check pos() after global match - should be undef in list context
    is(pos($string), undef, "After global match in list context: pos() should be undef");
};

subtest "Global match then regular match interaction" => sub {
    my $string = "ooaoaoao";
    my $rx = qr/o/;
    
    # First, perform global match
    my @matches = ($string =~ /$rx/g);
    is(scalar(@matches), 5, "Global match should find 5 'o' characters");
    is($', '', "After global match: \$' should be empty");
    is(pos($string), undef, "After global match: pos() should be undef");
    
    # Then perform regular match - should work from beginning of string
    my $regular_result = ($string =~ /$rx/);
    ok($regular_result, "Regular match after global should succeed");
    is($', 'oaoaoao', "After regular match: \$' should be 'oaoaoao'");
    is(pos($string), undef, "After regular match: pos() should still be undef");
};

subtest "Complex loop scenario from re/qr.t" => sub {
    # This is the exact scenario that was failing in t/re/qr.t
    my $output = "";
    my $rx = qr/o/;
    my $a = "ooaoaoao";

    # Count global matches
    my $foo = 0;
    $foo += () = ($a =~ /$rx/g);
    $output .= "$foo\n";
    is($foo, 5, "Global match count should be 5");

    # Test loop with literal regex
    $foo = 0;
    for ($foo += ($a =~ /o/); $' && ($' =~ /o/) && ($foo++) ; ) { ; }
    $output .= "1: $foo\n";
    is($foo, 5, "Loop with literal regex should execute 5 times");

    # Test loop with compiled regex (qr//)
    $foo = 0;
    for ($foo += ($a =~ /$rx/); $' && ($' =~ /$rx/) && ($foo++) ; ) { ; }
    $output .= "2: $foo\n";
    is($foo, 5, "Loop with compiled regex should execute 5 times");

    # Verify complete output matches expected
    my $expected = "5\n1: 5\n2: 5\n";
    is($output, $expected, "Complete output should match expected format");
};

subtest "Edge cases and special scenarios" => sub {
    # Test with empty string
    my $empty = "";
    $empty =~ /x/g;  # No matches
    is($', undef, "No matches: \$' should be undef");
    is(pos($empty), undef, "No matches: pos() should be undef");
    
    # Test with single character string
    my $single = "o";
    $single =~ /o/g;
    is($', '', "Single char match: \$' should be empty");
    is(pos($single), 1, "Single char match: pos() should be 1 after global match");
    
    # Reset pos for list context behavior
    pos($single) = undef;
    my @single_matches = ($single =~ /o/g);
    is($', '', "Single char match in list context: \$' should be empty");
    is(pos($single), undef, "Single char match in list context: pos() should be undef");
    
    # Test multiple global matches with different patterns
    my $multi = "abcabc";
    my @multi_matches = ($multi =~ /a/g);  # Matches at positions 0 and 3
    is($', 'bc', "Multiple global matches: \$' should be text after last match");
    is(pos($multi), undef, "Multiple global matches: pos() should be undef");
    
    # Verify subsequent regular match works
    my $result = ($multi =~ /b/);
    ok($result, "Regular match after global should work");
    is($', 'cabc', "After regular match: \$' should be 'cabc'");
};

subtest "Scalar vs List context differences" => sub {
    my $string = "ooaoaoao";
    
    # Global match in scalar context
    pos($string) = 0;  # Reset position
    my $scalar_result = ($string =~ /o/g);
    ok($scalar_result, "Global match in scalar context should succeed");
    isnt(pos($string), undef, "In scalar context: pos() should not be undef");
    
    # Reset and test list context
    pos($string) = undef;  # Reset position
    my @list_result = ($string =~ /o/g);
    is(scalar(@list_result), 5, "Global match in list context should find 5 matches");
    is(pos($string), undef, "In list context: pos() should be undef after global match");
};

done_testing();
