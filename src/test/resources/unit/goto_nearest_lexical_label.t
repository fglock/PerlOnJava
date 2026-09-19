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

done_testing;
