#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

eval q{method {} { $_, undef }};
like $@, qr/^Can't call method "method" on unblessed reference at /,
    'empty braces in indirect method syntax form a hash-reference invocant';

done_testing;
