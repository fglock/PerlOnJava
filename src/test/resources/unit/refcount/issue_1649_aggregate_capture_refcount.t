use strict;
use warnings;
use B qw(svref_2object);
use Test::More;

sub refcount { svref_2object($_[0])->REFCNT }

my @items = (1);
my $array = \@items;
is(refcount($array), 2, 'the lexical pad and explicit reference own the array');

my $first = sub { scalar @items };
is(refcount($array), 3, 'the first closure retains the captured array pad');

my $second = sub { scalar @items };
is(refcount($array), 4, 'a second closure adds a distinct capture owner');

undef $first;
is(refcount($array), 3, 'dropping one closure releases one capture owner');

undef $second;
is(refcount($array), 2, 'dropping the last closure releases its capture owner');

my %entries = (item => 1);
my $hash = \%entries;
is(refcount($hash), 2, 'the lexical pad and explicit reference own the hash');

my $first_hash = sub { scalar keys %entries };
is(refcount($hash), 3, 'the first closure retains the captured hash pad');

my $second_hash = sub { scalar keys %entries };
is(refcount($hash), 4, 'a second closure adds a distinct hash capture owner');

undef $first_hash;
is(refcount($hash), 3, 'dropping one closure releases one hash capture owner');

undef $second_hash;
is(refcount($hash), 2, 'dropping the last closure releases its hash capture owner');

done_testing;
