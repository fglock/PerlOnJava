use strict;
use warnings;
use Test::More;

my ($left, $middle, $right) = (10, 20, 30);
my $sum = sub { $left + $middle + $right };

is($sum->(), 60, 'captured integer sum');
$middle = 200;
is($sum->(), 240, 'closure reads current captured cells');

my $temporary = $sum->();
$temporary++;
is($sum->(), 240, 'returned rvalue does not alias a capture');

$left = '010';
is($sum->(), 240, 'string capture falls back to ordinary numeric addition');

done_testing;
