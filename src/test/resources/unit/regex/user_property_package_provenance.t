use strict;
use warnings;
use feature 'unicode_strings';
use threads;
use Test::More tests => 9;

my ($some, $other);
{
    package Some;
    sub Is_q { "71\n" }
    $some = qr/\p{Is_q}/;
}
{
    package Other;
    sub Is_q { "72\n" }
    $other = qr/\p{Is_q}/;
}

like('q', $some, 'bare property resolves in the qr construction package');
unlike('r', $some, 'first package excludes the other package member');
like('r', $other, 'same bare name resolves independently in another package');
unlike('q', $other, 'package-specific property caches do not collide');
like("$some", qr/Is_q/, 'stringification preserves the bare property spelling');

my $child = threads->create(sub {
    return 0 unless 'q' =~ $some && 'r' !~ $some;
    return 0 unless 'r' =~ $other && 'q' !~ $other;
    return 1;
});
is($child->join, 1, 'ithread snapshot retains both construction packages');

my $embedded;
{
    package Some;
    $embedded = qr/abc$some/;
}
like('abcq', $embedded, 'compiled qr reuse retains property provenance');
unlike('abcr', $embedded, 'compiled qr reuse retains package exclusions');

my $qualified = qr/\p{Other::Is_q}/;
like('r', $qualified, 'explicitly qualified property remains unchanged');
