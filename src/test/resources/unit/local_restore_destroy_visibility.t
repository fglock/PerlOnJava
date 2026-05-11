use strict;
use warnings;
use Test::More tests => 1;

our $x = 0;
our $saw_restored;

sub LocalRestoreDestroyVisibility::DESTROY {
    $saw_restored = !ref($x);
}

{
    local $x = \ bless {}, 'LocalRestoreDestroyVisibility';
    1;
}

ok($saw_restored, 'DESTROY during local restore sees the restored scalar value');
