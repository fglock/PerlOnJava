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
    ok(!$@ && $result, 'simple recursive pattern works');
}

# Test 2: Pattern with concatenation
{
    my $str = "hello world";
    my $result = eval { $str =~ /(??{"hel" . "lo"}) world/ };
    ok(!$@ && $result, 'concatenated runtime pattern works');
}

# Test 3: Dynamic pattern from variable
{
    my $str = "test123";
    my $pattern = "test";
    my $result = eval { $str =~ /(??{$pattern})\d+/ };
    ok(!$@ && $result, 'dynamic pattern from a lexical works');
}

# Test 4: Recursive pattern with alternation
{
    my $str = "foo";
    my $result = eval { $str =~ /(??{"f"})(?:oo|ar)/ };
    ok(!$@ && $result, 'runtime pattern composes with alternation');
}

# Test 5: Empty recursive pattern
{
    my $str = "abc";
    my $result = eval { $str =~ /a(??{""})bc/ };
    ok(!$@ && $result, 'empty runtime pattern works');
}

# Test 6: Recursive pattern that doesn't match
{
    my $str = "abc";
    my $result = eval { $str =~ /^(??{"x"})bc/ };
    ok(!$@ && !$result, 'non-matching runtime pattern fails without error');
}

# Test 7: Multiple recursive patterns
{
    my $str = "abcd";
    my $result = eval { $str =~ /(??{"a"})(??{"b"})cd/ };
    ok(!$@ && $result, 'multiple runtime patterns work');
}

# Test 8: Recursive pattern with regex metacharacters
{
    my $str = "123abc";
    my $result = eval { $str =~ /(??{"\\d+"})[a-z]+/ };
    ok(!$@ && $result, 'runtime pattern preserves regex metacharacters');
}

# Test 9: Difference between (?{...}) and (??{...})
{
    my $str = "abc";
    
    # First try (?{...}) - code execution, doesn't affect match
    my $code_block_result = eval { $str =~ /a(?{"x"})bc/ };  # Should match, "x" is just executed
    
    # Then try (??{...}) - pattern insertion
    my $recursive_result = eval { $str =~ /a(??{"x"})bc/ };  # Should NOT match, tries to match "x"
    
    ok(!$@ && $code_block_result && !$recursive_result,
        '(?{}) executes code while (??{}) inserts a pattern');
}

# Test 10: Recursive pattern in re/pat.t style
{
    my $str = "abc";
    my $result = eval { $str =~ /^(??{"a"})b/ };
    ok(!$@ && $result, 're/pat.t-style runtime pattern works');
}

done_testing();
