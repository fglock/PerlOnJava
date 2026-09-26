use strict;
use warnings;
use Test::More;

# Test::More imports skip().  A bareword after goto nevertheless names the
# local label, rather than calling that imported helper.
sub jump_to_local_skip {
    goto skip;
    return 'wrong';
  skip:
    return 'label';
}

is(jump_to_local_skip(), 'label',
   'goto label wins over an imported subroutine with the same name');

done_testing;
