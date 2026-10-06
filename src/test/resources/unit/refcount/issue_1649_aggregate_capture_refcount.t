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

sub make_scoped_array_capture {
    my @scoped_items = (1);
    my $array_ref = \@scoped_items;
    my $first_closure = sub { scalar @scoped_items };
    my $second_closure = sub { scalar @scoped_items };
    return ($array_ref, $first_closure, $second_closure);
}

my ($scoped_array, $first_array_closure, $second_array_closure) =
        make_scoped_array_capture();
is(refcount($scoped_array), 3,
    'scope exit releases the pad owner but keeps both closure owners');
undef $first_array_closure;
is(refcount($scoped_array), 2,
    'dropping one closure after scope exit releases one owner');
undef $second_array_closure;
is(refcount($scoped_array), 1,
    'dropping the last closure after scope exit preserves the external owner');

sub make_scoped_hash_capture {
    my %scoped_entries = (item => 1);
    my $hash_ref = \%scoped_entries;
    my $first_closure = sub { scalar keys %scoped_entries };
    my $second_closure = sub { scalar keys %scoped_entries };
    return ($hash_ref, $first_closure, $second_closure);
}

my ($scoped_hash, $first_hash_closure, $second_hash_closure) =
        make_scoped_hash_capture();
is(refcount($scoped_hash), 3,
    'hash scope exit releases the pad owner but keeps both closure owners');
undef $first_hash_closure;
is(refcount($scoped_hash), 2,
    'dropping one hash closure after scope exit releases one owner');
undef $second_hash_closure;
is(refcount($scoped_hash), 1,
    'dropping the last hash closure after scope exit preserves the external owner');

done_testing;
