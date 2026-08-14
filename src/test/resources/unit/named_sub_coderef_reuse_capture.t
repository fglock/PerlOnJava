#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 2;

my $called = 0;
sub callback { ++$called }

my $first = bless [\&callback], 'Local::CallbackHolder';
$first->[0]->();
is($called, 1, 'a named callback mutates its captured lexical');

$first = undef;
$called = 0;

my $second = bless [\&callback], 'Local::CallbackHolder';
$second->[0]->();
is($called, 1,
    'reusing a named callback still shares the outer lexical container');
