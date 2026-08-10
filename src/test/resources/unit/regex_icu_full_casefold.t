#!/usr/bin/env perl
use strict;
use warnings;
use utf8;
use Test::More tests => 6;

ok("\x{01F0}" =~ /^\x{006A}\x{030C}$/iu,
    'lowercase j with caron expands to its full fold');
ok("\x{006A}\x{030C}" =~ /^\x{01F0}$/iu,
    'j plus combining caron reverse-folds to one code point');

ok("\x{0390}" =~ /^\x{03B9}\x{0308}\x{0301}$/iu,
    'Greek lowercase character expands to its three-code-point fold');
ok("\x{03B9}\x{0308}\x{0301}" =~ /^\x{0390}$/iu,
    'Greek three-code-point sequence reverse-folds');

ok("\x{1E9E}" =~ /^\x{00DF}$/iu,
    'capital sharp s matches its sibling full-fold source');
ok("\x{00DF}" =~ /^\x{1E9E}$/iu,
    'sibling full-fold source matching is symmetric');
