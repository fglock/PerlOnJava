use strict;
use warnings;
use Test::More;

my $glob = *TARGET;
my $warning = '';
local $SIG{__WARN__} = sub { $warning .= shift };

is(ref $glob, '', 'a bare glob stringifies when assigned to a scalar');
is($glob * 'A', 0, 'a bare glob numifies to zero in multiplication');
like($warning, qr/\*main::TARGET.*isn't numeric in multiplication|isn't numeric in multiplication.*\*main::TARGET/,
    'glob multiplication emits the numeric warning');

sub coerce_number { $_[0] += 0 }
my $ok = eval { coerce_number(*STDERR); 1 };
ok(!defined $ok, 'a direct typeglob cannot be coerced for numeric assignment');
like($@, qr/Can't coerce GLOB to number in/, 'direct typeglob coercion reports the canonical error');

done_testing;
