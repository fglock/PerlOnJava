#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 2;

sub report_caller_line { (caller)[2] }

my $condition = 1;
if (0) {
    fail('unreachable');
}
elsif ($condition) {
    my $expected = __LINE__ + 1;
    is(report_caller_line(), $expected,
        'a call in an elsif block keeps its own statement line');
}

my $expected_do = __LINE__ + 1;
my $from_do = do {
    report_caller_line();
};
is($from_do, $expected_do,
    'a call in a do block inherits its enclosing statement line');
