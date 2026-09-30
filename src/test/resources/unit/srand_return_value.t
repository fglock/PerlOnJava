use strict;
use warnings;
use Test::More tests => 6;

my $seed = srand(1764);
is($seed, 1764, 'srand returns the seed it used');

$seed = srand(0);
ok($seed, 'zero seed return is true');
is(0 + $seed, 0, 'zero seed return is numerically zero');
is("$seed", '0 but true', 'zero seed return preserves its true string value');

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    srand(2**100);
}
like($warnings[0] // '', qr/^Integer overflow in srand at /,
    'oversized seed emits the overflow warning');
is(scalar @warnings, 1, 'overflow emits one warning');
