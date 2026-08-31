#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 1;

{
    package CallerBlockTerminatedCopline::Thing;
    sub new { bless {}, shift }
    sub report { return (caller)[2] }
}

# This is the exact shape from issue #1135.  The preceding package block has
# no semicolon, so Perl's COP lookahead reports the argument line, not the line
# containing the invocant or the final method call.
my $line = CallerBlockTerminatedCopline::Thing->new(
    value => 1,
)->report;
is($line, __LINE__ - 3, 'block-terminated predecessor preserves Perl COP line');
