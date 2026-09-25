use strict;
use warnings;
use Test::More tests => 2;

my @values;
$values[1] = 1;
foreach my $value ((), @values) {
    $value = 5;
}

is join('', @values), '55',
   'foreach lvalue lists preserve array holes while aliasing their cells';

my (@first, @second);
my $identity = '';
foreach my $ref (\@first, \@second) {
    $identity .= $ref == \@first ? 'a' : $ref == \@second ? 'b' : 'x';
}
is $identity, 'ab', 'foreach source preserves references instead of flattening them';
