use strict;
use warnings;
use Test::More;

my $match = 'foofoobar' =~ /(?<A>foo(?(R)bar))?(?1)/ ? $1 : undef;
is($match, 'foo', 'recursive numbered call restores its caller capture');

$match = 'xfoofoobar' =~ /(x)(?<A>foo(?(R&A)bar))?(?&A)/ ? $2 : undef;
is($match, 'foo', 'recursive named call restores its caller capture');

done_testing;
