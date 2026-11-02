use strict;
use warnings;
use Test::More;

sub capture_eval_caller_frame {
    my @frame = caller(1);
    return [$frame[3], $frame[4]];
}

my $frame = eval { capture_eval_caller_frame() };
is_deeply(
    $frame,
    ['(eval)', 0],
    'caller reports numeric false hasargs for eval frames',
);

done_testing;
