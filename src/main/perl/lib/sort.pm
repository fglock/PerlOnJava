package sort;
use strict;
use warnings;

our $VERSION = '2.05';

# PerlOnJava always uses a stable sort (Java's Arrays.sort is stable),
# so this pragma is a no-op. We accept the arguments silently.

sub import {
    my $class = shift;
    if (!@_) {
        require Carp;
        Carp::croak("sort pragma requires arguments");
    }
    # Accept 'stable', '_mergesort', '_qsort', 'defaults' silently
}

sub unimport {
    my $class = shift;
    # No-op: can't make Java sort unstable
}

sub current {
    warnings::warn("deprecated", "sort::current is deprecated, and will always return 'stable'")
        if warnings::enabled("deprecated");
    return 'stable';
}

1;
