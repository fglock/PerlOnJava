use strict;
use warnings;
use Test::More;

my $count = 0;
my $regex = qr/f(?{ ++$count })oo/;

ok('foo' =~ $regex, 'literal code block can occur before remaining pattern text');
ok($count > 0, 'prefix increment side effect is executed');

done_testing;
