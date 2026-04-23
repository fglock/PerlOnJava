#!/usr/bin/env perl
# Minimal reproduction of PerlOnJava bug:
# `local (HASH_OR_ARRAY_ELEMENT) = value;` inside eval-STRING-compiled
# subs is a no-op for the value assignment (scope restoration still works).
#
# See dev/modules/dbi_test_parity.md "Root cause of t/06attrs.t and
# t/08keeperr.t failures" for context. Blocks proper DBI::PurePerl
# error-message formatting.
#
# Run with both:
#   ./jperl dev/known-bugs/local_list_assign_eval_string.pl
#   perl    dev/known-bugs/local_list_assign_eval_string.pl
# and compare outputs.

use strict;
use warnings;

my $h = { x => 0 };
my @a = (0);

# Case A: direct file-scope compile — works on both
sub directA { local ($h->{x}) = 42; print "A: h->{x}=$h->{x}\n"; }
directA();
print "A: after: h->{x}=$h->{x}\n";

# Case B: eval-STRING compiled sub, hash-element, list form — BUG on jperl
my $subB = eval q{ sub { local ($h->{x}) = 99; print "B: h->{x}=$h->{x}\n"; } };
die $@ if $@;
$subB->();

# Case C: eval-STRING compiled sub, hash-element, SCALAR form — works
my $subC = eval q{ sub { local $h->{x} = 77; print "C: h->{x}=$h->{x}\n"; } };
die $@ if $@;
$subC->();

# Case D: eval-STRING compiled sub, array-element, list form — BUG on jperl
my $subD = eval q{ sub { local ($a[0]) = 88; print "D: a[0]=$a[0]\n"; } };
die $@ if $@;
$subD->();

print "\nExpected (real perl):\n";
print "A: h->{x}=42\nA: after: h->{x}=0\nB: h->{x}=99\nC: h->{x}=77\nD: a[0]=88\n";
