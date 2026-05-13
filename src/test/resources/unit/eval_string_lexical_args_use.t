# Regression: eval STRING must close over outer lexicals like perl(1)
# (Test::More::use_ok passes \@imports via my @args and uses \@{$args[0]} in eval).

use strict;
use warnings;
use Test::More tests => 1;

sub run_eval {
    my ( $code, @args ) = @_;
    my $out = eval $code;
    die $@ if $@;
    return $out;
}

my @want = qw(alpha beta gamma);
my $got = run_eval(
    q{ join '|', @{$args[0]} },
    \@want,
);

is( $got, join( '|', @want ), 'eval STRING sees outer @args in @{$args[0]}' );
