use strict;
use warnings;
use Test::More;

my $match = 'abbaccaddedcb' =~ /(?:\1|a)([bcd])\1(?:(?R)|e)\1/ ? $& : undef;
is($match, 'abbaccaddedcb', 'whole-pattern recursion preserves backreferences');

done_testing;
