use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 3;

{
    package Local::TiedReachability::Storage;
    our $DESTROYED = 0;
    sub DESTROY {
        $_[0]{active} = 0;
        $DESTROYED++;
    }

    package Local::TiedReachability::Array;
    sub TIEARRAY { bless { storage => $_[1] }, $_[0] }
    sub FETCHSIZE { 0 }
}

our $database = [];
our $weak_storage;

sub build_database {
    my $storage = bless { active => 1 },
                        'Local::TiedReachability::Storage';
    $weak_storage = $storage;
    weaken($weak_storage);
    tie @$database, 'Local::TiedReachability::Array', $storage;
}

build_database();
Internals::jperl_gc() if defined &Internals::jperl_gc;

my $handler = tied(@$database);
ok(defined($weak_storage), 'tied handler keeps its nested object reachable');
is($handler->{storage}{active}, 1,
   'weak sweep does not destroy an object held by a tied array handler');
is($Local::TiedReachability::Storage::DESTROYED, 0,
   'nested storage destructor has not fired while the tied array is live');
