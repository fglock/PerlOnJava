#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 6;

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

done_testing();
