use strict;
use warnings;
use Test::More;

my @seen;
for (1, 2) {
    {
        my $value = 3;
        Internals::SvREADONLY($value, 1);
        push @seen, $value;
    }
}
is_deeply \@seen, [3, 3],
    'a readonly lexical in a repeated block does not replace the next declaration cell';

use constant {
    READONLY_BLOCK_FIRST  => 10,
    READONLY_BLOCK_SECOND => 20,
};
is READONLY_BLOCK_FIRST, 10,
    'the first constant retains its independently readonly scalar value';
is READONLY_BLOCK_SECOND, 20,
    'the second constant retains its independently readonly scalar value';
is READONLY_BLOCK_FIRST + READONLY_BLOCK_SECOND, 30,
    'constant.pm can make each loop-local scalar readonly independently';

done_testing;
