#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 1;

my @seen;

sub capture_caller_context {
    my @caller = caller(1);
    my $wantarray = $caller[5];
    push @seen, defined($wantarray) ? ($wantarray ? 'list' : 'scalar') : 'void';
}

sub context_sensitive_function {
    capture_caller_context();
    return (1, 2, 3);
}

my $scalar = context_sensitive_function();
my @list = context_sensitive_function();
context_sensitive_function();

is_deeply(\@seen, [qw(scalar list void)], 'caller()[5] reports the inspected frame context');
