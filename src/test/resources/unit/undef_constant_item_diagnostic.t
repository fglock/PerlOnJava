#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

no strict 'subs';
eval q{ undef foo; };
like $@, qr/^Can't modify constant item in undef operator /,
    'undef rejects a bareword constant item';

done_testing;
