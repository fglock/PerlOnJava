use strict;
use warnings;
use Test::More;

my $source = 'abc';
ok(substr($source, 0, 1) eq 'a', 'direct substr string comparison');
ok(substr($source, 1, 1) == 0, 'direct substr numeric comparison');
ok('z' ne substr($source, 2, 1), 'direct substr comparison on right operand');

done_testing;
