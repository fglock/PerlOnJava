#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 2;

my $warned = 0;
{
    local $SIG{__WARN__} = sub {
        ++$warned if $_[0] =~ /Use of each\(\) on hash after insertion without resetting hash iterator results in undefined behavior/;
    };
    my %items = map { $_ => $_ } 'A' .. 'F';
    while (my ($key, $value) = each %items) {
        $items{"$key$key"} = $value;
    }
}
ok($warned > 0, 'insertion during each warns when warnings are enabled');

$warned = 0;
{
    no warnings 'internal';
    local $SIG{__WARN__} = sub { ++$warned };
    my %items = map { $_ => $_ } 'A' .. 'F';
    while (my ($key, $value) = each %items) {
        $items{"$key$key"} = $value;
    }
}
is($warned, 0, 'internal warning can be disabled');
