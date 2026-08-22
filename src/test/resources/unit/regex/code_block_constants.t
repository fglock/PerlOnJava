#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;

# Test regex (?{...}) code blocks with constant folding and $^R variable

# Test 1: Simple numeric constant
{
    my $str = "abc";
    ok($str =~ /a(?{ 42 })bc/, 'Simple numeric constant - pattern matches');
    is($^R, 42, 'Simple numeric constant - $^R should be 42');
}

# Test 2: String constant
{
    my $str = "test";
    ok($str =~ /t(?{ 'hello' })est/, 'String constant - pattern matches');
    is($^R, 'hello', 'String constant - $^R should be hello');
}

# Test 3: Arithmetic expression (constant folding)
{
    my $str = "xyz";
    ok($str =~ /x(?{ 2 + 2 })yz/, 'Arithmetic expression - pattern matches');
    is($^R, 4, 'Arithmetic expression constant folding - $^R should be 4');
}

# Test 4: Alternation - first branch
{
    my $str = "s";
    ok($str =~ / s (?{ 10111 }) | i (?{ 20222 }) /x, 'Alternation first branch - pattern matches');
    is($^R, 10111, 'Alternation first branch - $^R should be 10111');
}

# Test 5: Alternation - second branch
{
    my $str = "i";
    ok($str =~ / s (?{ 10111 }) | i (?{ 20222 }) /x, 'Alternation second branch - pattern matches');
    is($^R, 20222, 'Alternation second branch - $^R should be 20222');
}

# Test 6: Alternation - third branch
{
    my $str = "l";
    ok($str =~ / s (?{ 10111 }) | i (?{ 20222 }) | l (?{ 30333 }) /x, 'Alternation third branch - pattern matches');
    is($^R, 30333, 'Alternation third branch - $^R should be 30333');
}

# Test 7: Multiple code blocks in sequence
{
    my $str = "abc";
    ok($str =~ /a(?{ 1 })b(?{ 2 })c/, 'Multiple code blocks - pattern matches');
    # $^R should contain the result of the last code block
    is($^R, 2, 'Multiple code blocks - $^R contains last value');
}

# Test 8: Code block with /x modifier (whitespace)
{
    my $str = "test";
    ok($str =~ / t (?{ 99 }) est /x, 'Code block with /x modifier - pattern matches');
    is($^R, 99, 'Code block with /x modifier - $^R should be 99');
}

# Test 9: pack.t use case - map with alternation
{
    my @codes = ("s", "i", "l");
    my %val;
    @val{@codes} = map { 
        / s (?{ 10111 }) 
        | i (?{ 20222 }) 
        | l (?{ 30333 }) 
        /x; 
        $^R 
    } @codes;
    
    is($val{s}, 10111, 'pack.t use case - s value correct');
    is($val{i}, 20222, 'pack.t use case - i value correct');
    is($val{l}, 30333, 'pack.t use case - l value correct');
}

# Test 10: Large number constant
{
    my $str = "x";
    ok($str =~ /x(?{ 1234567890 })/, 'Large number constant - pattern matches');
    is($^R, 1234567890, 'Large number constant - $^R should be 1234567890');
}

# Test 11: Scientific notation
{
    my $str = "y";
    ok($str =~ /y(?{ 1.5e2 })/, 'Scientific notation - pattern matches');
    cmp_ok($^R, '==', 150, 'Scientific notation constant - $^R should be 150');
}

# Test 12: Negative number
{
    my $str = "z";
    ok($str =~ /z(?{ -42 })/, 'Negative number - pattern matches');
    is($^R, -42, 'Negative number constant - $^R should be -42');
}

# Test 13: Zero
{
    my $str = "a";
    ok($str =~ /a(?{ 0 })/, 'Zero constant - pattern matches');
    is($^R, 0, 'Zero constant - $^R should be 0');
}

# Test 14: Empty string
{
    my $str = "b";
    ok($str =~ /b(?{ '' })/, 'Empty string - pattern matches');
    is($^R, '', 'Empty string constant - $^R should be empty string');
}

# Test 15: Code block doesn't affect match position
{
    my $str = "hello world";
    ok($str =~ /hello(?{ 123 }) world/, 'Code block doesn\'t affect match position - pattern matches');
    is($^R, 123, 'Code block doesn\'t affect match position - $^R should be 123');
}

# Test 16: Undef constant
{
    my $str = "u";
    ok($str =~ /u(?{ undef })/, 'Undef constant - pattern matches');
    ok(!defined $^R, 'Undef constant - $^R should be undef');
}

# Test 17: $^R works without exposing internal callback captures
{
    my $str = "test";
    ok($str =~ /t(?{ 42 })est/, '$^R works - pattern matches');
    is($^R, 42, '$^R works correctly');
    is_deeply({ %+ }, {}, 'callback implementation captures stay private');
}

# Test 18: $^R works with regular named captures
{
    my $str = "abc";
    ok($str =~ /(?<first>a)(?{ 99 })(?<second>b)c/, '$^R with named captures - pattern matches');
    # Check that $^R got the code block value
    is($^R, 99, '$^R and named captures - $^R should be 99');
    # Check that regular captures still work
    is($+{first}, 'a', '$^R and named captures - first capture correct');
    is($+{second}, 'b', '$^R and named captures - second capture correct');
    is_deeply([sort keys %+], [qw(first second)],
        'only source named captures are public around code blocks');
}

# Test 19: Interpolated pattern with code block
{
    use re 'eval';
    my $var = '(?{ 999 })';
    my $str = "test";
    ok($str =~ /t${var}est/, 'interpolated code block pattern matches');
    is($^R, 999, 'interpolated code block publishes its result');
    is($&, 'test', 'interpolated code block preserves the whole match');
}

done_testing();
