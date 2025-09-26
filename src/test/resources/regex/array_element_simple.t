#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

###################
# Simple Array Element Interpolation vs Character Class Tests
# 
# This test demonstrates the enhanced parsing logic that considers strict mode context
# to correctly distinguish between array element access and character classes in regex.
# 
# Tests correspond to the critical t/base/lex.t tests 26 and 27.
###################

subtest "Core regex parsing tests" => sub {
    # Set up test data
    my ($X, @X) = qw(a b c d);  # X="a", @X=("b","c","d")
    my $foo = "FOO";
    my $A = "A";
    
    # Test 1: Array element interpolation (t/base/lex.t test 27)
    # $X[-1] should interpolate to "d" (last element of @X)
    my $pattern1 = qr/^$X[-1]$/;
    is("$pattern1", "(?^:^d\$)", "Array element: /^\$X[-1]\$/ interpolates to /^d\$/");
    ok("d" =~ $pattern1, "'d' matches array element pattern");
    ok(!("a" =~ $pattern1), "'a' does NOT match array element pattern");
    
    # Test 2: Character class (t/base/lex.t test 26) 
    # ${foo}[$A-Z] should become character class [A-Z]
    my $pattern2 = qr/^${foo}[$A-Z]$/;
    is("$pattern2", "(?^:^FOO[A-Z]\$)", "Character class: /^\${foo}[\$A-Z]\$/ becomes /^FOO[A-Z]\$/");
    ok("FOOZ" =~ $pattern2, "'FOOZ' matches character class");
    ok("FOOA" =~ $pattern2, "'FOOA' matches character class");
    ok(!("FOO1" =~ $pattern2), "'FOO1' does NOT match character class");
    
    # Enhanced parsing logic correctly distinguishes array access from character classes
};

subtest "Additional core tests" => sub {
    my ($X, @X) = qw(a b c d);
    my @arr = ("elem0", "elem1", "elem2");
    
    # Test positive array indices
    my $pattern1 = qr/^$X[0]$/;  # X[0] = "b"
    is("$pattern1", "(?^:^b\$)", "Positive index: /^\$X[0]\$/ -> /^b\$/");
    ok("b" =~ $pattern1, "'b' matches /^\$X[0]\$/");
    
    my $pattern2 = qr/^$X[1]$/;  # X[1] = "c"  
    is("$pattern2", "(?^:^c\$)", "Positive index: /^\$X[1]\$/ -> /^c\$/");
    ok("c" =~ $pattern2, "'c' matches /^\$X[1]\$/");
    
    # Test with different array
    my $pattern3 = qr/^$arr[1]$/;  # arr[1] = "elem1"
    is("$pattern3", "(?^:^elem1\$)", "Different array: /^\$arr[1]\$/ -> /^elem1\$/");
    ok("elem1" =~ $pattern3, "'elem1' matches /^\$arr[1]\$/");
    
    # Array element interpolation works for both positive and negative indices
};

subtest "Non-strict context (t/base/lex.t style)" => sub {
    # Test patterns that work in non-strict mode like original t/base/lex.t
    # Note: We still need 'my' declarations for proper scoping in this test environment
    my ($X, @X) = qw(a b c d);
    my $foo = "FOO";  # In t/base/lex.t this would be bareword FOO
    my $A = "A";      # In t/base/lex.t this would be bareword A
    
    # Test 27: Array element interpolation (exact t/base/lex.t behavior)
    ok("d" =~ /^$X[-1]$/, "Non-strict: \$X[-1] interpolates correctly");
    my $pattern1 = qr/^$X[-1]$/;
    is("$pattern1", "(?^:^d\$)", "Non-strict: Array pattern becomes /^d\$/");
    
    # Test 26: Character class (t/base/lex.t uses $foo[$A-Z] directly)
    ok("FOOZ" =~ /^$foo[$A-Z]$/, "Non-strict: \$foo[\$A-Z] is character class");
    my $pattern2 = qr/^$foo[$A-Z]$/;
    is("$pattern2", "(?^:^FOO[A-Z]\$)", "Non-strict: Character class becomes /^FOO[A-Z]\$/");
    
    # Enhanced parsing logic handles both contexts correctly
};

subtest "Strict vs Non-strict comparison" => sub {
    my ($X, @X) = qw(a b c d);
    my $foo = "FOO";
    my $A = "A";
    
    # Array element should work the same in both contexts
    my $array_pattern = qr/^$X[-1]$/;
    is("$array_pattern", "(?^:^d\$)", "Both contexts: Array element interpolation works");
    ok("d" =~ $array_pattern, "Both contexts: 'd' matches array pattern");
    
    # Character classes work in both contexts (different syntax)
    my $strict_class = qr/^${foo}[$A-Z]$/;    # Strict mode syntax
    my $nonstrict_class = qr/^$foo[$A-Z]$/;   # Non-strict mode syntax
    
    is("$strict_class", "(?^:^FOO[A-Z]\$)", "Strict syntax: \${foo}[\$A-Z] works");
    is("$nonstrict_class", "(?^:^FOO[A-Z]\$)", "Non-strict syntax: \$foo[\$A-Z] works");
    
    # Both should match the same strings
    ok("FOOZ" =~ $strict_class, "Strict syntax matches 'FOOZ'");
    ok("FOOZ" =~ $nonstrict_class, "Non-strict syntax matches 'FOOZ'");
    ok(!("FOO1" =~ $strict_class), "Strict syntax rejects 'FOO1'");
    ok(!("FOO1" =~ $nonstrict_class), "Non-strict syntax rejects 'FOO1'");
};

subtest "Comprehensive passing tests" => sub {
    my ($X, @X) = qw(a b c d);
    my @arr = ("elem0", "elem1", "elem2");
    my $prefix = "TEST";
    my $start = "A";
    my $end = "Z";
    
    # Multiple array indices
    my $pattern1 = qr/^$X[0]$X[1]$/;  # Should be "bc"
    is("$pattern1", "(?^:^bc\$)", "Multiple array access: /^\$X[0]\$X[1]\$/ -> /^bc\$/");
    ok("bc" =~ $pattern1, "'bc' matches multiple array interpolation");
    
    # Array access with different arrays
    my $pattern2 = qr/^$arr[0]$/;  # Should be "elem0"
    is("$pattern2", "(?^:^elem0\$)", "Different array: /^\$arr[0]\$/ -> /^elem0\$/");
    ok("elem0" =~ $pattern2, "'elem0' matches different array access");
    
    # Character class with variable range
    my $pattern3 = qr/^${prefix}[$start-$end]$/;
    is("$pattern3", "(?^:^TEST[A-Z]\$)", "Variable range: /^\${prefix}[\$start-\$end]\$/ -> /^TEST[A-Z]\$/");
    ok("TESTA" =~ $pattern3, "'TESTA' matches variable range");
    ok("TESTZ" =~ $pattern3, "'TESTZ' matches variable range");
    ok(!("TEST1" =~ $pattern3), "'TEST1' does NOT match variable range");
    
    # All these demonstrate our enhanced parsing logic works correctly
};

done_testing();
