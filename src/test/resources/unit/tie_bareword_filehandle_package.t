#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 1;

sub IO::File::TIEARRAY {
    die "tie used the bareword filehandle object\n";
}

fileno FOO;

my @array;
eval { tie @array, "FOO" };

like(
    $@,
    qr/^Can't locate object method "TIEARRAY" via package "FOO"/,
    'tie class string does not dispatch through a same-named filehandle'
);
