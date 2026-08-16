use strict;
use warnings;
use Test::More;

my @seen;
CORE::for (my $i = 0; $i < 3; $i++) {
    push @seen, $i;
}
is_deeply(\@seen, [0, 1, 2], 'CORE::for parses as a three-part loop');

my $count = 0;
CORE::while ($count < 2) {
    $count++;
}
is($count, 2, 'CORE::while parses as a loop');

CORE::if ($count == 2) {
    pass('CORE::if parses as a conditional');
}

done_testing;
