use strict;
use warnings;

use Scalar::Util qw(looks_like_number reftype);
use Test::More tests => 6;

my $warned = 0;
my $number;
{
    local $SIG{__WARN__} = sub { $warned = 1 };
    $number = 0 + "0 but true";
}
is($number, 0, '0 but true numifies to zero');
ok(!$warned, '0 but true numifies without a numeric warning');
ok(looks_like_number("0 but true"), '0 but true looks like a number');

my $regexp_scalar = ${qr/xyz/};
is(ref($regexp_scalar), '', 'dereferenced regexp is a first-class scalar');
is(ref(\$regexp_scalar), 'REGEXP', 'reference to first-class regexp has REGEXP type');
is(reftype(\$regexp_scalar), 'REGEXP', 'reftype preserves first-class regexp type');
