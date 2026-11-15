use strict;
use warnings;
use Test::More;

tie my $value, 'Issue1548::TiedScalar';
$value = 'stored';

is($Issue1548::TiedScalar::STORE_HAS_ARGS, 1,
    'caller reports a fresh argument frame for tied STORE');

done_testing;

package Issue1548::TiedScalar;

our $STORE_HAS_ARGS;

sub TIESCALAR { bless {}, shift }

sub STORE {
    my @frame = caller(0);
    $STORE_HAS_ARGS = $frame[4];
}
