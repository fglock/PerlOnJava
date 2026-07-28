use strict;
use warnings;
use Test::More;

our @captured;
BEGIN {
    $SIG{__WARN__} = sub { push @captured, @_ };
}

use constant AUTOLOAD => 1;
my $sum = 1 + 'not numeric';
like(
    join('', @captured),
    qr/isn't numeric in addition/,
    'warning-producing constant arithmetic reaches a compile-time handler',
);

like(
    join('', @captured),
    qr/Constant name 'AUTOLOAD' is a Perl keyword/,
    'warnings::warn reaches a compile-time handler',
);

is($sum, 1, 'non-numeric constant arithmetic retains its value');
is(AUTOLOAD(), 1, 'keyword-named constant remains installed');

done_testing;
