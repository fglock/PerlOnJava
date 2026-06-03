#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 13;

my @branch_cases = (
    ['a/a', 1, 'a'],
    ['b/b', 1, 'b'],
    ['a/b', 0, undef],
    ['b/a', 0, undef],
);

for my $case (@branch_cases) {
    my ($s, $match, $capture) = @$case;
    if ($s =~ /(?:(?<x>a)\/\k<x>|(?<x>b)\/\k<x>)/) {
        ok($match, "duplicate named backref branch match $s");
        is($+{x}, $capture, "duplicate named backref branch capture $s");
    } else {
        ok(!$match, "duplicate named backref branch reject $s");
    }
}

my @post_cases = (
    ['aa', 1, 'a'],
    ['bb', 1, 'b'],
    ['ab', 0, undef],
    ['ba', 0, undef],
);

for my $case (@post_cases) {
    my ($s, $match, $capture) = @$case;
    if ($s =~ /(?:(?<x>a)|(?<x>b))\k<x>/) {
        ok($match, "duplicate named backref after alternation match $s");
        is($+{x}, $capture, "duplicate named backref after alternation capture $s");
    } else {
        ok(!$match, "duplicate named backref after alternation reject $s");
    }
}

'3/5/09' =~ /^\s*(?:(?<m>\d\d?)(?<sep>[\s\.\/\-])(?<d>\d\d?)\k<sep>(?<y>\d\d\d\d)|(?<m>\d\d?)(?<sep>[\s\.\/\-])(?<d>\d\d?)\k<sep>(?<y>\d\d)|(?<m>\d\d?)(?<sep>[\s\.\/\-])(?<d>\d\d?))\s*$/;
is_deeply({ y => $+{y}, m => $+{m}, d => $+{d}, sep => $+{sep} },
          { y => '09', m => '3', d => '5', sep => '/' },
          'Date::Manip-style duplicate named separator backref matches two-digit year');
