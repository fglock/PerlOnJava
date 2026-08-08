#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;

my $original = umask;
my $previous = umask(0077);

is $previous, $original, 'setting a restrictive umask returns the previous mask';
is umask, 0077, 'umask accepts owner permission bits';

is umask($original), 0077, 'restoring umask returns the restrictive mask';
is umask, $original, 'original umask is restored';

done_testing;
