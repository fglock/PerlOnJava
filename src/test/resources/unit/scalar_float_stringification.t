use strict;
use warnings;
use Test::More tests => 1;

my $value = 30326 + 25 * ((29736 - 30326) / 37);
is("$value", '29927.3513513513',
    'floating point stringification uses Perl-compatible ties-to-even rounding');
