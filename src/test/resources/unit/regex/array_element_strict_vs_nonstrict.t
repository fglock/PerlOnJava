#!/usr/bin/perl
use Test::More;

###################
# Tests to demonstrate the INTENDED difference between strict and non-strict parsing
# 
# These tests show what SHOULD be different between the two contexts.
# Both tests should pass with standard Perl, but may behave differently with PerlOnJava
# depending on how we implement the strict vs non-strict distinction.
###################

subtest "Non-strict context: More permissive parsing" => sub {
    # NO 'use strict' - non-strict context
    
    # Set up test data
    ($X, @X) = qw(a b c d);
    $foo = "FOO";
    $A = "A";
    $Z = "Z";
    
    # These patterns should work in non-strict mode
    # The key insight: non-strict mode allows more ambiguous patterns
    
    # Test 1: Character class with literal range (should work in non-strict)
    my $pattern1 = qr/^$foo[A-Z]$/;  # Literal character class range
    is("$pattern1", "(?^:^FOO[A-Z]\$)", "Non-strict: Literal range \$foo[A-Z] works");
    ok("FOOB" =~ $pattern1, "Non-strict: 'FOOB' matches literal range");
    ok(!("FOO1" =~ $pattern1), "Non-strict: 'FOO1' does NOT match literal range");
    
    # Test 2: Array element interpolation (should work in both contexts)
    my $pattern2 = qr/^$X[-1]$/;  # Array element access
    is("$pattern2", "(?^:^d\$)", "Non-strict: Array element \$X[-1] interpolates");
    ok("d" =~ $pattern2, "Non-strict: 'd' matches array element");
    
    # Test 3: Ambiguous pattern that non-strict should handle permissively
    $scalar_var = "TEST";
    $char_idx = "0";
    my $pattern3 = qr/^${scalar_var}[$char_idx]$/;  # Use explicit syntax to avoid @scalar_var error
    # In non-strict mode, this should be treated as character class
    is("$pattern3", "(?^:^TEST[0]\$)", "Non-strict: \${scalar_var}[\$char_idx] treated as character class");
    ok("TEST0" =~ $pattern3, "Non-strict: 'TEST0' matches character class pattern");
};

subtest "Strict context: More conservative parsing" => sub {
    use strict;
    use warnings;
    
    # Set up test data with proper declarations
    my ($X, @X) = qw(a b c d);
    my $foo = "FOO";
    my $A = "A";
    my $Z = "Z";
    
    # In strict mode, we should be more conservative about ambiguous patterns
    # The key insight: strict mode requires more explicit disambiguation
    
    # Test 1: Explicit syntax for character classes (should work in strict)
    my $pattern1 = qr/^${foo}[$A-$Z]$/;  # Explicit scalar syntax
    is("$pattern1", "(?^:^FOO[A-Z]\$)", "Strict: Explicit syntax \${foo}[\$A-\$Z] works");
    ok("FOOB" =~ $pattern1, "Strict: 'FOOB' matches explicit character class");
    ok(!("FOO1" =~ $pattern1), "Strict: 'FOO1' does NOT match explicit character class");
    
    # Test 2: Array element interpolation (should work in both contexts)
    my $pattern2 = qr/^$X[-1]$/;  # Array element access
    is("$pattern2", "(?^:^d\$)", "Strict: Array element \$X[-1] interpolates");
    ok("d" =~ $pattern2, "Strict: 'd' matches array element");
    
    # Test 3: Ambiguous pattern that strict mode should handle conservatively
    my $scalar_var = "TEST";
    my $char_idx = "0";
    # In strict mode, this might be treated differently than non-strict
    # The behavior difference will be implemented based on our logic
    my $pattern3 = qr/^${scalar_var}[$char_idx]$/;  # Use explicit syntax
    # For now, both should behave the same, but this is where we'll see the difference
    is("$pattern3", "(?^:^TEST[0]\$)", "Strict: \${scalar_var}[\$char_idx] behavior");
    ok("TEST0" =~ $pattern3, "Strict: 'TEST0' matches in strict mode");
};

subtest "Direct comparison: Same patterns in both contexts" => sub {
    # This subtest demonstrates patterns that should behave identically
    # regardless of strict vs non-strict context
    
    # Non-strict block
    my ($non_strict_result1, $non_strict_result2);
    {
        no strict;
        ($X, @X) = qw(a b c d);
        $foo = "FOO";
        $A = "A";
        
        my $pattern1 = qr/^$X[-1]$/;  # Array element
        my $pattern2 = qr/^${foo}[$A-Z]$/;  # Explicit character class
        
        $non_strict_result1 = "$pattern1";
        $non_strict_result2 = "$pattern2";
    }
    
    # Strict block
    my ($strict_result1, $strict_result2);
    {
        use strict;
        my ($X, @X) = qw(a b c d);
        my $foo = "FOO";
        my $A = "A";
        
        my $pattern1 = qr/^$X[-1]$/;  # Array element
        my $pattern2 = qr/^${foo}[$A-Z]$/;  # Explicit character class
        
        $strict_result1 = "$pattern1";
        $strict_result2 = "$pattern2";
    }
    
    # These should be identical in both contexts
    is($non_strict_result1, $strict_result1, "Array element patterns identical in both contexts");
    is($non_strict_result2, $strict_result2, "Explicit character class patterns identical in both contexts");
    
    # Both should produce the expected results
    is($non_strict_result1, "(?^:^d\$)", "Array element produces expected result");
    is($non_strict_result2, "(?^:^FOO[A-Z]\$)", "Character class produces expected result");
};

done_testing();
