#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;

# Test regex (??{...}) recursive/dynamic patterns
# These patterns insert a regex at runtime based on code execution

# Test 1: Simple constant pattern insertion
{
    my $str = "abc";
    my $result = eval { $str =~ /^(??{"a"})bc/ };
    if ($@) {
        like($@, qr/recursive regex patterns not implemented/, '(??{...}) not implemented yet (expected)');
    } else {
        ok($result, 'Simple recursive pattern works (Perl behavior)');
    }
}

# Test 2: Pattern with concatenation
{
    my $str = "hello world";
    my $result = eval { $str =~ /(??{"hel" . "lo"}) world/ };
    if ($@) {
        like($@, qr/recursive regex patterns not implemented/, 'Concatenated pattern not implemented (expected)');
    } else {
        ok($result, 'Concatenated pattern works (Perl behavior)');
    }
}

# Test 3: Dynamic pattern from variable
{
    my $str = "test123";
    my $pattern = "test";
    my $result = eval { $str =~ /(??{"test"})\d+/ };
    if ($@) {
        like($@, qr/recursive regex patterns not implemented/, 'Dynamic-like pattern not implemented (expected)');
    } else {
        ok($result, 'Dynamic-like pattern works (Perl behavior)');
    }
}

# Test 4: Recursive pattern with alternation
{
    my $str = "foo";
    my $result = eval { $str =~ /(??{"f"})(?:oo|ar)/ };
    if ($@) {
        like($@, qr/recursive regex patterns not implemented/, 'Recursive with alternation not implemented (expected)');
    } else {
        ok($result, 'Recursive with alternation works (Perl behavior)');
    }
}

# Test 5: Empty recursive pattern
{
    my $str = "abc";
    my $result = eval { $str =~ /a(??{""})bc/ };
    if ($@) {
        like($@, qr/recursive regex patterns not implemented/, 'Empty recursive pattern not implemented (expected)');
    } else {
        ok($result, 'Empty recursive pattern works (Perl behavior)');
    }
}

# Test 6: Recursive pattern that doesn't match
{
    my $str = "abc";
    my $result = eval { $str =~ /^(??{"x"})bc/ };
    if ($@) {
        like($@, qr/recursive regex patterns not implemented/, 'Non-matching recursive not implemented (expected)');
    } else {
        ok(!$result, 'Non-matching recursive pattern correctly fails (Perl behavior)');
    }
}

# Test 7: Multiple recursive patterns
{
    my $str = "abcd";
    my $result = eval { $str =~ /(??{"a"})(??{"b"})cd/ };
    if ($@) {
        like($@, qr/recursive regex patterns not implemented/, 'Multiple recursive patterns not implemented (expected)');
    } else {
        ok($result, 'Multiple recursive patterns work (Perl behavior)');
    }
}

# Test 8: Recursive pattern with regex metacharacters
{
    my $str = "123abc";
    my $result = eval { $str =~ /(??{"\\d+"})[a-z]+/ };
    if ($@) {
        like($@, qr/recursive regex patterns not implemented/, 'Recursive with regex metacharacters not implemented (expected)');
    } else {
        ok($result, 'Recursive with regex metacharacters works (Perl behavior)');
    }
}

# Test 9: Difference between (?{...}) and (??{...})
{
    my $str = "abc";
    
    # First try (?{...}) - code execution, doesn't affect match
    my $code_block_result = eval { $str =~ /a(?{"x"})bc/ };  # Should match, "x" is just executed
    
    # Then try (??{...}) - pattern insertion
    my $recursive_result = eval { $str =~ /a(??{"x"})bc/ };  # Should NOT match, tries to match "x"
    
    if ($@ && $@ =~ /recursive regex patterns not implemented/) {
        like($@, qr/recursive regex patterns not implemented/, 'Difference test: recursive not implemented (expected)');
    } elsif (!$@) {
        ok($code_block_result && !$recursive_result, '(?{}) vs (??{}) behave differently as expected');
    } else {
        fail('Unexpected error: ' . $@);
    }
}

# Test 10: Recursive pattern in re/pat.t style
{
    my $str = "abc";
    my $result = eval { $str =~ /^(??{"a"})b/ };
    if ($@) {
        like($@, qr/recursive regex patterns not implemented/, 're/pat.t style recursive not implemented (expected)');
    } else {
        ok($result, 're/pat.t style recursive works (Perl behavior)');
    }
}

done_testing();
