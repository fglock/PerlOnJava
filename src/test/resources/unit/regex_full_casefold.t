#!/usr/bin/env perl
use strict;
use warnings;
use utf8;
use Test::More tests => 8;

ok("ss" =~ /^\x{00DF}$/iu, 'sharp s pattern matches its full fold');
ok("\x{00DF}" =~ /^ss$/iu, 'full fold pattern matches sharp s');
ok("\x{017F}\x{017F}" =~ /^\x{00DF}$/iu,
    'simple-fold components participate in a full fold');
ok("st" =~ /^[\x{FB06}]$/iu, 'full fold works from a character class');
ok("\x{FB06}" =~ /^st$/iu, 'ligature matches the reverse full fold');
ok("\x{FB03}" =~ /^ffi$/iu, 'three-character full fold is expanded');

ok("\x{00DF}" =~ /^ss$/ia, '/a permits Unicode-to-ASCII case folds');
ok("\x{00DF}" !~ /^ss$/iaa, '/aa forbids Unicode-to-ASCII case folds');
