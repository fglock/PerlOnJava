#!/usr/bin/perl
# NO 'use strict' - this is a TRUE non-strict test like t/base/lex.t
use Test::More;

###################
# Non-Strict Array Element Interpolation Tests - PASSING CASES
# 
# This test file runs in TRUE non-strict mode (no 'use strict')
# to verify our enhanced parsing logic handles non-strict contexts correctly.
# 
# This should trigger the non-strict path in our parsing logic:
# parser.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_VARS) should return FALSE
#
# These are the tests that PASS with both standard Perl and PerlOnJava
###################

subtest "True non-strict parsing (like t/base/lex.t)" => sub {
    # Set up test data using barewords (like original t/base/lex.t)
    ($X, @X) = qw(a b c d);  # No 'my' - barewords in non-strict mode
    $foo = FOO;              # Bareword FOO (not "FOO")
    $A = A;                  # Bareword A (not "A")
    
    # Test 27: Array element interpolation (exact t/base/lex.t behavior)
    ok("d" =~ /^$X[-1]$/, "Non-strict: \$X[-1] interpolates correctly");
    my $pattern1 = qr/^$X[-1]$/;
    is("$pattern1", "(?^:^d\$)", "Non-strict: Array pattern becomes /^d\$/");
    
    # Test 26: Character class (t/base/lex.t uses $foo[$A-Z] directly)
    ok("FOOZ" =~ /^$foo[$A-Z]$/, "Non-strict: \$foo[\$A-Z] is character class");
    my $pattern2 = qr/^$foo[$A-Z]$/;
    is("$pattern2", "(?^:^FOO[A-Z]\$)", "Non-strict: Character class becomes /^FOO[A-Z]\$/");
    
    # This tests the non-strict path in our enhanced parsing logic
};

subtest "Non-strict array access patterns" => sub {
    # These patterns work in non-strict mode with both Perl and PerlOnJava
    ($Y, @Y) = qw(x y z);
    
    # Array element access
    my $pattern1 = qr/^$Y[0]$/;  # Should be "y"
    is("$pattern1", "(?^:^y\$)", "Non-strict: Array access works");
    ok("y" =~ $pattern1, "Non-strict: 'y' matches array pattern");
    
    # These are the patterns that work reliably in both environments
};

subtest "Verify non-strict context is active" => sub {
    # These should work in non-strict mode but would fail in strict mode
    $test_var = BAREWORD_VALUE;  # This should work in non-strict
    is($test_var, "BAREWORD_VALUE", "Barewords work in non-strict mode");
    
    # Test that our regex parsing works with simple bareword variables
    my $pattern = qr/^$test_var$/;
    is("$pattern", "(?^:^BAREWORD_VALUE\$)", "Simple bareword interpolation works");
    ok("BAREWORD_VALUE" =~ $pattern, "Bareword matches correctly");
    
    # This confirms we're truly in non-strict mode and basic functionality works
};

done_testing();
