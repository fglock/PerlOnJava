#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 4;

my $text = 'bird';
for (1 .. 2) {
    if ($text =~ m?bird?g) {
        is(pos($text), 4, 'first match-once global match publishes pos');
    } else {
        is(pos($text), undef, 'failed match-once global retry clears pos');
    }
}

$_ = '1';
for (1 .. 2) {
    if (m?\d?g) {
        is(pos, 1, 'first default-variable match-once match publishes pos');
    } else {
        is(pos, undef, 'failed default-variable match-once retry clears pos');
    }
}
