#!/usr/bin/env perl
# A closure passed directly as an argument is a temporary CODE reference.
# Its captured lexical must be released when the argument frame returns.
use strict;
use warnings;
use Test::More tests => 1;

our $destroyed = 0;

{
    package CapturedOwner;
    sub DESTROY { ++$main::destroyed }
}

sub discard_callback { return }

{
    my $owner = bless {}, 'CapturedOwner';
    discard_callback(sub { $owner });
}

is($destroyed, 1, 'direct callback argument releases its captured lexical');
