#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 1;

{
    no feature 'evalbytes';
    eval q{evalbytes 'foo'};
    like($@, qr/syntax error/, 'evalbytes is a syntax error when its feature is disabled');
}
