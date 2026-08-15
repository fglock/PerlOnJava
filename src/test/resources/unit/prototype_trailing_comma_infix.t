#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 2;

my $mask = "\x01";

my $logical_or = vec $mask, 0, 1, || vec $mask, 1, 1,;
ok($logical_or, 'trailing comma before logical-or ends fixed-prototype arguments');

my $logical_and = vec $mask, 0, 1, && !vec $mask, 1, 1,;
ok($logical_and, 'trailing comma before logical-and ends fixed-prototype arguments');
