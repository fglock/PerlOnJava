use strict;
use warnings;
use Test::More;

my $value = 10;
my $scalar = sub { ++$value };

is($scalar->(), 11, 'scalar-context closure return has its scalar value');
is($scalar->(), 12, 'a later scalar return does not retain prior result state');

my @list = $scalar->();
is_deeply(\@list, [13], 'list-context caller receives the scalar return as a list');

my $multiple = sub { return 1, 2, 3 };
is($multiple->(), 3, 'scalar context still collapses a multi-value return');
is_deeply([$multiple->()], [1, 2, 3], 'list context retains every returned value');

done_testing;
