use strict;
use warnings;
use feature 'switch';
use Test::More;

my $match;
given ('Hello, world!') {
    when (/lo/) { $match = 'first'; continue }
    when (/^Hello,/) { $match = 'second'; continue }
}

is $match, 'second', 'bare regex when clauses test the localized topic';

done_testing;
