#!/usr/bin/env perl

use strict;
use warnings;
use Data::Dumper;
use Test::More tests => 5;

local $Data::Dumper::Terse = 1;
local $Data::Dumper::Useqq = 0;

my @words = qw(weight 2);
is(Dumper($words[1]), "'2'\n",
    'an untouched short numeric string stays quoted');

my $number = 2;
is(Dumper($number), "2\n",
    'a short integer scalar stays numeric');

my $copied = $words[1];
is(Dumper($copied), "'2'\n",
    'assignment preserves untouched string state');

my $numified = $words[1];
my $ignored = 0 + $numified;
is(Dumper($numified), "2\n",
    'numeric context makes a short numeric string dump numerically');

my %attributes;
${\$attributes{weight}} = $words[1];
is(Dumper($attributes{weight}), "'2'\n",
    'scalar-reference assignment preserves untouched string state');
