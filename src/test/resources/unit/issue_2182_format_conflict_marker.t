#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 2;

format ISSUE_2182 =
Type      Source    Target
==========================
.

ok defined *ISSUE_2182{FORMAT}, 'format picture may contain a conflict-marker-like line';

my $invalid = 'my $value;' . "\n=======\n";
eval $invalid;
like $@, qr/Version control conflict marker.*near \"=======\"/s,
    'conflict markers outside format pictures remain errors';
