use strict;
use warnings;
use Test::More;

my $value = 0;
{
    while (1) {
      OUTER:
        $value += 10;
        last;
    }
    is $value, 10, 'the inner loop label does not shadow the enclosing goto target';
    goto OUTER if $value == 10;
    $value += 10;
  OUTER:
    is $value, 10, 'goto chooses the nearest label in the enclosing block';
}

my ($sum, $reentered) = (0, 0);
for my $item (0 .. 1) {
  AGAIN:
    $sum = 0;
  AGAIN:
    $sum += 10;
    if (!$reentered++) {
        goto AGAIN;
    }
}
is $sum, 10, 'a repeated label in a folded foreach body targets its first occurrence';
is $reentered, 3, 'the goto does not restart the enclosing program';

done_testing;
