use strict;
use warnings;
use Test::More tests => 4;
use Scalar::Util qw(weaken);

our @DESTROYED;

{
    package StashDeleteDetachedGlobOwner::Probe;

    sub DESTROY {
        push @main::DESTROYED, 'value';
    }
}

my $object = bless {}, 'StashDeleteDetachedGlobOwner::Probe';
my $weak = $object;
weaken($weak);
{
    no strict 'refs';
    ${'StashDeleteDetachedGlobOwner::value'} = $object;
}
undef $object;

my $detached_glob = delete $StashDeleteDetachedGlobOwner::{value};
ok(defined $weak, 'the returned detached glob keeps its scalar referent alive');
is_deeply(\@DESTROYED, [], 'stash deletion does not run DESTROY on the detached value');

my $scalar_slot = *{$detached_glob}{SCALAR};
is($$scalar_slot, $weak, 'the detached glob retains its original scalar slot');

undef $scalar_slot;
undef $detached_glob;
is_deeply(\@DESTROYED, ['value'], 'releasing the detached glob destroys its value once');
