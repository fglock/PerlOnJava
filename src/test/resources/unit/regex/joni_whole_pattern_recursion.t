use strict;
use warnings;
use Test::More;

my $input = 'phoofoofoobarbarbarr';
my $match = $input =~ /foo(?R)?bar/ ? $& : undef;
is($match, 'foofoobarbar', 'whole-pattern recursion with (?R)');

done_testing;
