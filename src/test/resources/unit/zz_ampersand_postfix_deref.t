#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 4;

sub component_table {
    return [ [ [qw(alpha beta)], [qw(gamma)] ] ];
}

sub component_by_index {
    my $index = shift;
    return if !defined(my $component = &component_table->[0][$index]);
    return @$component;
}

is_deeply([component_by_index(0)], [qw(alpha beta)],
    'postfix dereference indexes an ampersand call result');
is_deeply([component_by_index(1)], [qw(gamma)],
    'postfix dereference preserves the selected nested array');
is_deeply([component_by_index(2)], [],
    'defined assignment returns early for a missing nested element');
ok(defined(&component_table),
    'direct ampersand definition probes remain intact');
