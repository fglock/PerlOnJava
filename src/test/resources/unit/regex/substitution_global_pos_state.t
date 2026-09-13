use strict;
use warnings;
use Test::More;

my $value = '123456';
pos($value) = 4;
my $count = $value =~ s/\d\d\G/7/g;
is($count, 1, 'global substitution starting at pos() replaces once');
is($value, '12756', 'global substitution preserves the expected suffix');

$value = '123456';
pos($value) = 4;
$count = $value =~ s/\d\d(?=\d\G)/78/g;
is($count, 1, 'lookahead global substitution starting at pos() replaces once');
is($value, '178456', 'lookahead global substitution preserves the expected suffix');

done_testing();
