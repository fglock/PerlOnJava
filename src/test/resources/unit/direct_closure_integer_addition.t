use strict;
use warnings;
use Test::More;

# A zero-argument closure with one use of each captured scalar is the narrow
# shape the JVM may enter without materialising @_ or calling the generated
# closure method.  These assertions describe the ordinary Perl contract, not
# the implementation path.
my ($left, $middle, $right) = (10, 20, 30);
my $sum = sub { $left + $middle + $right };

is($sum->(), 60, 'captured integer sum');
$middle = 200;
is($sum->(), 240, 'closure reads current captured cells');

my $temporary = $sum->();
$temporary++;
is($sum->(), 240, 'returned rvalue does not alias a capture');

# A string-valued capture must retain normal numeric conversion and its PV
# channel rather than entering the integer-only fast path.
$left = '010';
is($sum->(), 240, 'string capture falls back to ordinary numeric addition');

done_testing;
