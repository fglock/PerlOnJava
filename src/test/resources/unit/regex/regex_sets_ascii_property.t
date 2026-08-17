use strict;
use warnings;
use Test::More tests => 3;

my $pattern = qr/(?[ [k] + \p{Blk=ASCII} ])/i;

ok("k" =~ $pattern, 'case-folded literal remains in the set');
ok("A" =~ $pattern, 'ASCII block property remains in the set');
ok("\x{17f}" !~ $pattern, '/i does not expand the ASCII block property');
