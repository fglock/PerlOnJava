#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 3;
use B ();
use Scalar::Util qw(refaddr reftype);
use threads;

my $referent = bless {}, 'Local::ThreadType';
my $address = refaddr($referent);

my $result = threads->create(sub {
    my $b_object = bless \$address, 'B::SV';
    my $restored = $b_object->object_2svref;
    return [
        defined($restored),
        ref($restored),
        reftype($restored) eq 'HASH',
    ];
})->join;

ok($result->[0], 'B::SV restores a reference from its cloned-thread address');
is($result->[1], 'Local::ThreadType', 'the restored reference keeps its blessing');
ok($result->[2], 'the restored reference retains its underlying type');
