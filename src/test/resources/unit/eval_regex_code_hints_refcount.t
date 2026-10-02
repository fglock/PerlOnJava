#!/usr/bin/env perl

use strict;
use warnings;
use feature qw(:5.10);
use Test::More tests => 1;

sub refcount_is {
    my (undef, $expected, $description) = @_;
    is(&Internals::SvREFCNT($_[0]) + 1, $expected, $description);
}

my $expected = ($^H & 0x20000) ? 2 : 1;
my ($hints, $text);
$text = 'a';
$text =~ s/a/$hints = \%^H; qq( qq() );/ee;

refcount_is($hints, $expected,
    'eval during a replacement does not retain an extra hints reference');
