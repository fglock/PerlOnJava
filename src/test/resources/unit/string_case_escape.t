use strict;
use warnings;
use Test::More tests => 35;

# Basic case modifier tests
my $result1 = "abc\l\Udefgh\Ei";
is($result1, 'abcdEFGHi', 'Literal: abc\\l\\Udefgh\\Ei should become abcdEFGHi');

# Variable interpolation test
my $v = "defg";
my $result2 = "abc\l\U${v}h\Ei";
is($result2, 'abcdEFGHi', 'Interpolated: abc\\l\\U\${v}h\\Ei should become abcdEFGHi');

# Empty variable interpolation
$v = "";
$result2 = "abc\l\U${v}h\Ei";
is($result2, 'abchi', 'Interpolated with empty var: abc\\l\\U\${v}h\\Ei should become abchi');

# Mixed case modifiers
$v = "";
$result2 = "abc\L lower \U${v} upper \Ei";
is($result2, 'abc lower  UPPER i', 'Interpolated \\L + \\U');

# Nested case modifiers with content between
my $text = "Hello World";
is("\Uabc\Ldef\Eghi\E", "ABCdefghi", 'Nested \\U with \\L inside');
is("\Labc\UDEF\Eghi\E", "abcDEFghi", 'Nested \\L with \\U inside');

# Single character modifiers
is("hello \uworld", "hello World", '\\u should uppercase first character only');
is("HELLO \lWORLD", "HELLO wORLD", '\\l should lowercase first character only');
is("hello \u", "hello ", '\\u at end of string should have no effect');

# Multiple single-char modifiers
is("\u\u\uhello", "Hello", 'Multiple \\u should only affect first character');
is("\l\l\lHELLO", "hELLO", 'Multiple \\l should only affect first character');

# Case modifiers with special characters
is("\U123!@#\E", "123!@#", '\\U with non-letters should leave them unchanged');
is("\L123!@#\E", "123!@#", '\\L with non-letters should leave them unchanged');

# Unclosed case modifiers
is("start \Uend", "start END", 'Unclosed \\U should affect rest of string');
is("start \Lend", "start end", 'Unclosed \\L should affect rest of string');

# Empty case modifiers
is("\U\E", "", 'Empty \\U...\\E should produce empty string');
is("\L\E", "", 'Empty \\L...\\E should produce empty string');

# Case modifiers with escape sequences inside
is("\U\n\t\E", "\n\t", '\\U with escape sequences should not affect them');
is("hello\U\x20world\E", "hello WORLD", '\\U with hex escape space');

# Conflicting case modifiers
is("\Uhello\Lworld\E", "HELLOworld", '\\U followed by \\L should switch modes');
is("\Lhello\UWORLD\E", "helloWORLD", '\\L followed by \\U should switch modes');

# Case modifiers with array interpolation
my @arr = ('one', 'two', 'three');
is("\U@arr\E", "ONE TWO THREE", '\\U with array interpolation');
is("\L@arr\E", "one two three", '\\L with array interpolation');

# Complex nesting
is("pre\Louter\Uinner\Emid\Epost", "preouterINNERmidpost", 'Complex nested case modifiers');

# Single-char with interpolation
$v = "test";
is("hello \u$v world", "hello Test world", '\\u with variable interpolation');
is("HELLO \l$v WORLD", "HELLO test WORLD", '\\l with variable interpolation');

# Tests for \Q...\E (quotemeta)
is("\Qhello.world\E", "hello\\.world", '\\Q should escape metacharacters');
is("\U\Qhello.world\E\E", "HELLO\\.WORLD", '\\U with \\Q should uppercase and escape');
is("\Qtest[]*+?\Erest", "test\\[\\]\\*\\+\\?rest", '\\Q should escape regex metacharacters');

# \Q with special characters and variables
$v = "a.b*c";
is("\Q$v\E", "a\\.b\\*c", '\\Q should escape metacharacters in variables');

# Edge case: \l\U and \u\L combinations in eval
# These should fail at runtime when parsed
my $error_case1 = eval { "\l\Utext" };
is($error_case1, "tEXT", '\\l\\U with content should work');

my $error_case2 = eval { "\u\Ltext" };
is($error_case2, "Text", '\\u\\L with content should work');

# Case modifiers with dereferenced variables
my $ref = \"HELLO";
is("test \U$$ref\E end", "test HELLO end", '\\U with dereferenced scalar');

# Multiple \E markers
is("\Uhello\E\E\E", "HELLO", 'Multiple \\E should be harmless');

# Verify single-char modifiers don't affect multiple interpolations
$v = "test";
my $w = "word";  
is("\u$v$w", "Testword", '\\u should only affect first interpolation');

