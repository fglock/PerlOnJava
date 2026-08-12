use strict;
use warnings;
use Test::More tests => 2;

my $readable = '';
vec($readable, fileno(STDIN), 1) = 1;

is(select($readable, undef, undef, 0.1), 1,
    'select reports redirected stdin EOF as readable');
is(vec($readable, fileno(STDIN), 1), 1,
    'select retains the stdin readiness bit at EOF');
