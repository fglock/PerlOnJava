use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 3;

{
    package Local::TiedOwner;
    our $destroyed = 0;
    sub TIEHASH { bless {}, shift }
    sub DESTROY { ++$destroyed }
}

my $hash = {};
tie %$hash, 'Local::TiedOwner';
my $owner = $hash;
my $object = tied %$hash;
weaken($hash);
weaken($object);
undef $owner;

ok(!defined $hash, 'weak reference to abandoned tied hash clears immediately');
ok(!defined $object, 'weak reference to tie object clears immediately');
is($Local::TiedOwner::destroyed, 1, 'tie object DESTROY runs immediately');
