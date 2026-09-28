use strict;
use warnings;
use Test::More;

use lib 'src/test/resources/unit/caller_use_stack';

eval 'use CallerUseStack::A; 1' or die $@;

is(scalar @CallerUseStack::C::frames, 10,
    'nested use exposes require, wrapper, and import caller frames');
like($CallerUseStack::C::frames[0], qr{CallerUseStack/B\.pm:2\z},
    'innermost use frame has the B module location');
like($CallerUseStack::C::frames[3], qr{CallerUseStack/A\.pm:2\z},
    'outer use frame has the A module location');
like($CallerUseStack::C::frames[6], qr{^\(eval \d+\):1\z},
    'eval use frame preserves its eval source location');

done_testing;
