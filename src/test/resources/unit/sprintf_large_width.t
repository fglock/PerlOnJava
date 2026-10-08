use strict;
use warnings;
use Test::More tests => 4;

my $width = 10965;

my $static = sprintf("%${width}s", 'x');
is(length($static), $width, 'static width above 8192 is accepted');
is(substr($static, -1), 'x', 'static width keeps the value right-aligned');

my $dynamic = sprintf('%*s', $width, 'x');
is(length($dynamic), $width, 'dynamic width above 8192 is accepted');
is(substr($dynamic, -1), 'x', 'dynamic width keeps the value right-aligned');
