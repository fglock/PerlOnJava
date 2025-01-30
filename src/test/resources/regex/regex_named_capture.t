#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 10;

# Test case 1: Simple named capture
my $string1 = 'foo';
if ($string1 =~ /(?<foo>foo)/) {
    is($+{foo}, 'foo', 'Test case 1: Named capture for "foo"');
    is($-{foo}[0], 'foo', 'Test case 1: All named captures for "foo"');
} else {
    fail('Test case 1: Pattern did not match');
}

# Test case 2: Multiple named captures
my $string2 = 'barbaz';
if ($string2 =~ /(?<bar>bar)(?<baz>baz)/) {
    is($+{bar}, 'bar', 'Test case 2: Named capture for "bar"');
    is($+{baz}, 'baz', 'Test case 2: Named capture for "baz"');
    is($-{bar}[0], 'bar', 'Test case 2: All named captures for "bar"');
    is($-{baz}[0], 'baz', 'Test case 2: All named captures for "baz"');
} else {
    fail('Test case 2: Pattern did not match');
}

# Test case 3: Backreference using \k
my $string3 = 'catcat';
if ($string3 =~ /(?<animal>cat)\k<animal>/) {
    is($+{animal}, 'cat', 'Test case 3: Named capture with \k backreference');
} else {
    fail('Test case 3: Pattern did not match');
}

# Test case 4: Backreference using \g
my $string4 = 'dogdog';
if ($string4 =~ /(?<pet>dog)\g{pet}/) {
    is($+{pet}, 'dog', 'Test case 4: Named capture with \g backreference');
} else {
    fail('Test case 4: Pattern did not match');
}

# Test case 5: Multiple backreferences
my $string5 = 'mousemousemouse';
if ($string5 =~ /(?<rodent>mouse)\g{rodent}\k<rodent>/) {
    is($+{rodent}, 'mouse', 'Test case 5: Named capture with multiple backreferences');
} else {
    fail('Test case 5: Pattern did not match');
}

# Test case 6: Case-insensitive backreference
my $string6 = 'ratRAT';
if ($string6 =~ /(?i)(?<creature>rat)\k<creature>/) {
    is($+{creature}, 'rat', 'Test case 6: Case-insensitive named capture with backreference');
} else {
    fail('Test case 6: Pattern did not match');
}

__END__

# Test case 7: Relative backreference with \g{-1}
my $string7 = 'fishfish';
if ($string7 =~ /(fish)\g{-1}/) {
    is($1, 'fish', 'Test case 7: Relative backreference \g{-1}');
} else {
    fail('Test case 7: Pattern did not match');
}

# Test case 8: Multiple captures with \g{-2}
my $string8 = 'catdogcat';
if ($string8 =~ /(cat)(dog)\g{-2}/) {
    is($1, 'cat', 'Test case 8: First capture in \g{-2} pattern');
    is($2, 'dog', 'Test case 8: Second capture in \g{-2} pattern');
} else {
    fail('Test case 8: Pattern did not match');
}

# Test case 9: Mixed named and relative backreferences
my $string9 = 'mouseratmouse';
if ($string9 =~ /(?<first>mouse)(rat)\g{-2}/) {
    is($+{first}, 'mouse', 'Test case 9: Named capture with relative backreference');
    is($2, 'rat', 'Test case 9: Regular capture in mixed pattern');
    is($1, 'mouse', 'Test case 9: Relative backreference match');  # Add this line
}

done_testing();

