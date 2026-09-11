#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

eval q{{s//${}/; //}};
like $@, qr/syntax error/,
    'empty braced interpolation is invalid in a substitution replacement';

done_testing;
