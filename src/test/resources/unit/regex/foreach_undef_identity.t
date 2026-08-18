use strict;
use warnings;
use Test::More;

sub {
    is(\$_[0], \undef, 'undef argument aliases canonical undef before foreach');
    foreach (@_) {
        is(\$_, \$_[0], 'implicit foreach aliases the exact argument cell');
        is(\$_, \undef, 'implicit foreach cell is canonical undef directly');
        is(eval { \$_ }, \$_, 'eval preserves the foreach cell identity');
        is(eval { \$_ }, \undef, 'eval foreach cell is canonical undef');
    }
}->(undef);

done_testing;
