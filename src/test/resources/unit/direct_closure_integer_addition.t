use strict;
use warnings;
use Test::More;

my ($left, $middle, $right) = (10, 20, 30);
my $sum = sub { $left + $middle + $right };

my $first_result = $sum->();
is($first_result, 60, 'captured integer sum in scalar context');
$middle = 200;
my $mutated_result = $sum->();
is($mutated_result, 240, 'closure reads current captured cells');

my $temporary = $sum->();
$temporary++;
is($sum->(), 240, 'returned rvalue does not alias a capture');

$left = '010';
my $fallback_result = $sum->();
is($fallback_result, 240, 'string capture falls back to ordinary numeric addition');

done_testing;
