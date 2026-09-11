#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

our $value;
no strict 'subs';
eval q{ ($value, bareword) = (1, 2); };
like $@, qr/^Can't modify constant item in list assignment/,
    'list assignment rejects a bareword constant item';

done_testing;
