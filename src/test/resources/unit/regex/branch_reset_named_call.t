use strict;
use warnings;
use Test::More tests => 10;

my $regex = eval { qr/(?|(?<digit>1)|(?<digit>2))(?&digit)/ };
ok(defined $regex, 'duplicate named branch-reset call compiles');
is($@, '', 'compilation reports no error');

ok('11' =~ $regex, 'first alternative can call the named group');
is($1, '1', 'first alternative publishes its numbered capture');
is($+{digit}, '1', 'first alternative publishes its named capture');

ok('21' =~ $regex, 'second alternative calls the leftmost named definition');
is($1, '2', 'second alternative preserves its numbered capture');
is($+{digit}, '2', 'second alternative preserves its named capture');

ok('12' !~ $regex, 'named call does not target the second definition');
ok('22' !~ $regex, 'second alternative still calls the leftmost definition');
