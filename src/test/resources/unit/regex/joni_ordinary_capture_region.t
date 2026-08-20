use strict;
use warnings;
use Test::More;

my $match = 'ababa' =~ /(?<pair>ab)(?&pair)(?<tail>a)/ ? "$1-$2" : undef;
is($match, 'ab-a', 'ordinary named subpattern call retains earlier and later captures');

done_testing;
