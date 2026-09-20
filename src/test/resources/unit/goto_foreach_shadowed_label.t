use strict;
use warnings;
use Test::More;

my ($error, $outer_label_was_entered) = ('', 0);
eval {
    my $value = 0;
    for (0 .. 1) {
      TARGET:
        $value += 10;
        last;
    }
    goto TARGET if $value == 10;
};
$error = $@;

goto AFTER_OUTER_TARGET;
TARGET:
{
    $outer_label_was_entered = 1;
}
AFTER_OUTER_TARGET:

like(
    $error,
    qr/Can't "goto" into the middle of a foreach loop/,
    'goto selects the shadowing foreach label and rejects an illegal entry',
);
is($outer_label_was_entered, 0, 'goto does not resolve to a later outer label');

done_testing;
