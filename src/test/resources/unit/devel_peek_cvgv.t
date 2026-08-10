use strict;
use warnings;
use Test::More;
use Devel::Peek qw(CvGV);

sub named_sub { return }

is(
    '' . CvGV(\&named_sub),
    '*main::named_sub',
    'CvGV returns the canonical typeglob for a named coderef',
);

done_testing;
