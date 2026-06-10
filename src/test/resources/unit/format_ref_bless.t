use strict;
use warnings;
use Test::More tests => 6;
use Scalar::Util qw(blessed reftype);

format FH =
.

my $missing = do {
    no warnings 'once';
    *NO_SUCH_FORMAT{FORMAT};
};
ok(!defined($missing), 'missing format slot is undef');

my $fmt = *FH{FORMAT};
is(ref($fmt), 'FORMAT', 'format slot reports FORMAT');
is(reftype($fmt), 'FORMAT', 'Scalar::Util sees a FORMAT ref');
is(blessed($fmt), undef, 'plain format ref is unblessed');

bless $fmt, 'FormatRef';
is(ref($fmt), 'FormatRef', 'format ref can be blessed');
is(reftype($fmt), 'FORMAT', 'blessed format keeps FORMAT reftype');
