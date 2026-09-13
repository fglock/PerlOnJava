#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

my $identifier = 'F' x 1020;
eval $identifier;
like $@, qr/^Identifier too long at /,
    'a bare identifier at Perl identifier capacity is rejected';

done_testing;
