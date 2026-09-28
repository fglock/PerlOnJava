use strict;
use warnings;
use Test::More;

my @shells;
my $last_index = $#shells++;
$shells[$last_index]{HOST} = 'beach';

is($last_index, -1, 'postincrement of an empty array length returns -1');
is($shells[0]{HOST}, 'beach', 'negative index addresses the newly created final slot');
is(scalar @shells, 1, 'the write vivifies one array slot');

done_testing;
