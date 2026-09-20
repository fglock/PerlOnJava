use strict;
use warnings;
use Test::More;

my $ok = eval q{ goto target; CORE::given(1) { target: } 1 };
ok(!$ok, 'goto cannot enter a given block');
like($@, qr/Can't "goto" into a "given" block/,
    'goto reports the given-entry diagnostic');

done_testing;
