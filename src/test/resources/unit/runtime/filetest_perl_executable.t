use strict;
use warnings;
use Test::More tests => 2;

ok(-B $^X, 'the active Perl executable is binary');
ok(!-T $^X, 'the active Perl executable is not text');
