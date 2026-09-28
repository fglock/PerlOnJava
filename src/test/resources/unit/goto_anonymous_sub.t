use strict;
use warnings;
use Test::More;

eval { goto sub {} };
like $@, qr/^Can't goto subroutine from an eval-block/,
    'goto anonymous sub from an eval block reports the Perl diagnostic';

eval { my @output = sort { goto sub {} } 1, 2; };
like $@, qr/^Can't goto subroutine outside a subroutine/,
    'goto anonymous sub in a sort comparator reports the Perl diagnostic';

sub goto_anonymous_sub { goto sub {} }
eval { my @output = sort goto_anonymous_sub 1, 2; };
like $@, qr/^Can't goto subroutine from a sort sub/,
    'goto anonymous sub from a named sort comparator reports the Perl diagnostic';

done_testing;
