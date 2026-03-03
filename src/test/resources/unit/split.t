use strict;
use warnings;
use Test::More;

# Pattern is omitted or a single space character
my $string = "  This  is   a test  string  ";
my @result = split(' ', $string);
is_deeply(\@result, ['This', 'is', 'a', 'test', 'string'], 'split with space pattern');
is(scalar(@result), 5, 'split with space pattern count');

# Pattern is /^/ with multiline modifier
$string = "This\nis\na\ntest\nstring";
@result = split(/^/m, $string);
is_deeply(\@result, ["This\n", "is\n", "a\n", "test\n", "string"], 'split with multiline pattern');
is(scalar(@result), 5, 'split with multiline pattern count');

# Pattern with capturing groups
$string = 'a1b2c3';
@result = split(/(\d+)/, $string);
is_deeply(\@result, ['a', '1', 'b', '2', 'c', '3'], 'split with capturing groups');

# Negative limit
$string = 'This is a test string';
@result = split(' ', $string, -1);
is_deeply(\@result, ['This', 'is', 'a', 'test', 'string'], 'split with negative limit');

# Omitted or zero limit with trailing empty fields
$string = 'This is a test string ';
@result = split(' ', $string);
is_deeply(\@result, ['This', 'is', 'a', 'test', 'string'], 'split with trailing spaces');

# Empty pattern (split between characters)
$string = 'abc';
@result = split(//, $string);
is_deeply(\@result, ['a', 'b', 'c'], 'split with empty pattern');

# Literal string pattern
$string = 'a,b,c';
@result = split(',', $string);
is_deeply(\@result, ['a', 'b', 'c'], 'split with literal pattern');

# Pattern with no capturing groups and limit zero
$string = 'a b c ';
@result = split(' ', $string, 0);
is_deeply(\@result, ['a', 'b', 'c'], 'split with zero limit');

# Pattern with capturing groups and limit zero
$string = 'a1b2c3';
@result = split(/(\d+)/, $string, 0);
is_deeply(\@result, ['a', '1', 'b', '2', 'c', '3'], 'split with capturing groups and zero limit');

# Pattern with capturing groups and positive limit
$string = 'a1b2c3';
@result = split(/(\d+)/, $string, 3);
is_deeply(\@result, ['a', '1', 'b', '2', 'c3'], 'split with capturing groups and positive limit');

$string = 'one, two, three';
my $pattern = ',+\s*';
@result = split $pattern, $string;
is_deeply(\@result, ['one', 'two', 'three'], 'split with variable regex pattern');

$string = 'a.b.c';
$pattern = '\.';
@result = split $pattern, $string;
is_deeply(\@result, ['a', 'b', 'c'], 'split with escaped dot in variable');

$string = 'abc';
$pattern = '.';
@result = split $pattern, $string, -1;
is_deeply(\@result, ['', '', '', ''], 'split with dot as regex (matches any char)');

done_testing();
