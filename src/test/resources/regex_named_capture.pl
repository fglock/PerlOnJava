#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

# Test case 1: Simple named capture
my $string1 = 'foo';
if ($string1 =~ /(?<foo>foo)/) {
    is($+{foo}, 'foo', 'Test case 1: Named capture for "foo"');
} else {
    fail('Test case 1: Pattern did not match');
}

# Test case 2: Multiple named captures
my $string2 = 'barbaz';
if ($string2 =~ /(?<bar>bar)(?<baz>baz)/) {
    is($+{bar}, 'bar', 'Test case 2: Named capture for "bar"');
    is($+{baz}, 'baz', 'Test case 2: Named capture for "baz"');
} else {
    fail('Test case 2: Pattern did not match');
}

## # Test case 3: Overlapping named captures
## my $string3 = 'foobar';
## if ($string3 =~ /(?<foo>foo)(?<bar>bar)|(?<foo>foobar)/) {
##     is($+{foo}, 'foo', 'Test case 3: Overlapping named capture for "foo"');
## } else {
##     fail('Test case 3: Pattern did not match');
## }

done_testing();
