use strict;
use warnings;
use B qw(svref_2object);
use Scalar::Util qw(weaken);
use Test::More;

our ($array_destroyed, $hash_destroyed) = (0, 0);

{
    package Issue1649::BlessedArrayCapture;
    sub DESTROY { ++$main::array_destroyed }

    package Issue1649::BlessedHashCapture;
    sub DESTROY { ++$main::hash_destroyed }
}

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

sub make_blessed_array_capture {
    my @items = (1);
    my $object = bless \@items, 'Issue1649::BlessedArrayCapture';
    my $first_closure = sub { scalar @items };
    my $second_closure = sub { scalar @items };
    return ($object, $first_closure, $second_closure);
}

my ($blessed_array, $first_blessed_array_closure, $second_blessed_array_closure) =
        make_blessed_array_capture();
my $weak_blessed_array = $blessed_array;
weaken($weak_blessed_array);
is(refcount($blessed_array), 3,
    'a blessed array remains owned by its external reference and two closures');
undef $first_blessed_array_closure;
is(refcount($blessed_array), 2, 'a blessed array releases one closure owner');
undef $second_blessed_array_closure;
is(refcount($blessed_array), 1, 'a blessed array retains its external owner');
undef $blessed_array;
ok(!defined($weak_blessed_array), 'dropping the final blessed array owner clears its weak observer');
is($array_destroyed, 1, 'the blessed array is destroyed exactly once');

sub make_blessed_hash_capture {
    my %items = (item => 1);
    my $object = bless \%items, 'Issue1649::BlessedHashCapture';
    my $first_closure = sub { scalar keys %items };
    my $second_closure = sub { scalar keys %items };
    return ($object, $first_closure, $second_closure);
}

my ($blessed_hash, $first_blessed_hash_closure, $second_blessed_hash_closure) =
        make_blessed_hash_capture();
my $weak_blessed_hash = $blessed_hash;
weaken($weak_blessed_hash);
is(refcount($blessed_hash), 3,
    'a blessed hash remains owned by its external reference and two closures');
undef $first_blessed_hash_closure;
is(refcount($blessed_hash), 2, 'a blessed hash releases one closure owner');
undef $second_blessed_hash_closure;
is(refcount($blessed_hash), 1, 'a blessed hash retains its external owner');
undef $blessed_hash;
ok(!defined($weak_blessed_hash), 'dropping the final blessed hash owner clears its weak observer');
is($hash_destroyed, 1, 'the blessed hash is destroyed exactly once');

done_testing;
