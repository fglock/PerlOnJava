use strict;
use warnings;
use Test::More tests => 4;

ok('foofoobar' =~ /(?<A>foo(?(R)bar))?(?1)/,
    'R is true inside a subpattern call');
ok('ok' =~ /(?(R)bad|ok)/,
    'R is false outside a subpattern call');
ok('foofoobar' =~ /(?<A>foo(?(R1)bar))?(?1)/,
    'R1 identifies a numbered recursive group');
ok('xfoofoobar' =~ /(x)(?<A>foo(?(R&A)bar))?(?&A)/,
    'R&name identifies a named recursive group');
