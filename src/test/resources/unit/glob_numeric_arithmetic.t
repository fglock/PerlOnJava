use strict;
use warnings;
use Test::More;

my $glob = *TARGET;
my $warning = '';
local $SIG{__WARN__} = sub { $warning .= shift };

is($glob * 'A', 0, 'a bare glob numifies to zero in multiplication');
like($warning, qr/\*main::TARGET.*isn't numeric in multiplication|isn't numeric in multiplication.*\*main::TARGET/,
    'glob multiplication emits the numeric warning');

done_testing;
