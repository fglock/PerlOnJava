use strict;
use warnings;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Test::More;

my %values = map { $_ => $_ } 'a' .. 'f';
my $matched = 0;
given ('a') {
    when (@values{'a' .. 'c'}) { $matched = 1 }
}
is $matched, 1, 'when smartmatches against a hash slice';

done_testing;
