#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 9;

my $s = 'aa';
is($s =~ s/(?<of>a)//g, 2, 'global substitution with named capture matches twice');
is($+{of}, 'a', 'global substitution leaves the last named capture in %+');
is_deeply($-{of}, ['a'], 'global substitution leaves named capture alternatives in %-');

$s = 'bc';
$s =~ s/(?<of>b)//g;
$s =~ s/(?<of>x)//g;
is($+{of}, 'b', 'failed substitution preserves previous named capture in %+');
is_deeply($-{of}, ['b'], 'failed substitution preserves previous named capture in %-');

'z' =~ /z/;
ok(!exists $+{of}, 'successful match without named captures clears %+');
ok(!exists $-{of}, 'successful match without named captures clears %-');

my $dup = 'ab';
$dup =~ s/(?<x>a)|(?<x>b)//g;
is($+{x}, 'b', 'duplicate named capture reports last global substitution branch in %+');
is_deeply($-{x}, [undef, 'b'], 'duplicate named capture alternatives survive exhausted matcher');
