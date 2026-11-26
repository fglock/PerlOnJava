use strict;
use warnings;
use Test::More tests => 2;

our $destroyed = 0;

{
    package ManyUntrackedHashOwnersTarget;
    sub DESTROY { ++$main::destroyed }
}

my $shared = bless {}, 'ManyUntrackedHashOwnersTarget';
my %owners;
$owners{$_} = $shared for 1 .. 256;
undef $shared;

is($destroyed, 0, 'all hash value owners keep the shared object alive');

%owners = ();
is($destroyed, 1, 'clearing the hash releases the shared object once');
