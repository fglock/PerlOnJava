use strict;
use warnings;
use Test::More tests => 14;

my $pattern = qr/^((?|([a-z]+)-(\d+)|([a-z]+):([A-Z]+)?:(\d+)))$/;

ok('abc-12' =~ /$pattern/, 'first branch matches');
is($1, 'abc-12', 'outer capture is preserved');
is($2, 'abc', 'first branch reuses capture two');
is($3, '12', 'first branch reuses capture three');
ok(!defined $4, 'unused higher capture remains undef');
is(scalar @-, 4, 'capture offset arrays end at the highest active branch capture');

ok('abc::12' =~ /$pattern/, 'second branch matches without optional capture');
is($2, 'abc', 'second branch reuses capture two');
ok(!defined $3, 'unmatched optional capture remains undef');
is($4, '12', 'second branch exposes its fourth capture');
ok(!defined $-[3], 'unmatched branch-reset capture has an absent start');

my @captures = ('abc:X:12' =~ /$pattern/);
is_deeply(\@captures, ['abc:X:12', 'abc', 'X', '12'],
          'list context returns native branch-reset numbering');
is($-[3], 4, 'participating optional capture start is correct');
is($+[4], 8, 'final capture end is correct');
