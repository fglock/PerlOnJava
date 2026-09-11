#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

our $buffer;
no strict 'subs';
eval q{ read($buffer, FILE, 1); };
like $@, qr/^Can't modify constant item in read /,
    'read rejects a bareword buffer as a constant item';

done_testing;
