use strict;
use warnings;
use Test::More tests => 6;
use FindBin;
use lib "$FindBin::Bin/fixtures/MaybeXS/lib";
use List::Util::PP qw(pairgrep pairfirst pairmap);

is(scalar(pairgrep { $b & 2 } one => 1, two => 2, three => 3), 2,
    'upstream pairgrep returns matching pair count in scalar context');
is_deeply([pairgrep { $b % 2 } one => 1, two => 2, three => 3],
    [one => 1, three => 3], 'upstream pairgrep returns matched pairs in list context');

my @kvlist = (one => 1, two => 2);
pairgrep { $b++ } @kvlist;
is_deeply(\@kvlist, [one => 2, two => 3],
    'upstream pairgrep aliases source elements through localized globs');

is_deeply([pairfirst { length $a == 5 } one => 1, two => 2, three => 3],
    [three => 3], 'upstream pairfirst returns the first matching pair');
is(scalar(pairfirst { length $a == 5 } one => 1, two => 2, three => 3), 1,
    'upstream pairfirst reports a match in scalar context');
is_deeply([pairmap { uc $a => $b } one => 1, two => 2, three => 3],
    [ONE => 1, TWO => 2, THREE => 3], 'upstream pairmap returns transformed pairs');
