use strict;
use warnings;
use Test::More;

our $destroyed = 0;

{
    package VoidDiscardZeroCountProbe;

    sub new { bless {}, shift }
    sub DESTROY { $main::destroyed++ }
}

VoidDiscardZeroCountProbe->new();

is($destroyed, 1,
   'a zero-count result discarded in void context is destroyed at the statement boundary');

done_testing();
