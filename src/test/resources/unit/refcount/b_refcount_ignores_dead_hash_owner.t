#!/usr/bin/env perl
use strict;
use warnings;

use B qw(svref_2object);
use Test::More;

our (%KEEP, $REFCOUNT);
$KEEP{target} = bless {}, 'DeadHashOwnerTarget';

sub add_temporary_hash_owner {
    my %temporary;
    $temporary{copy} = $KEEP{target};
}

add_temporary_hash_owner();

{
    package DeadHashOwnerProbe;
    sub DESTROY {
        $main::REFCOUNT = B::svref_2object($main::KEEP{target})->REFCNT;
    }
}

my $probe = bless {}, 'DeadHashOwnerProbe';
undef $probe;

is($REFCOUNT, 1, 'B excludes a hash owner after its hash leaves scope');

done_testing;
