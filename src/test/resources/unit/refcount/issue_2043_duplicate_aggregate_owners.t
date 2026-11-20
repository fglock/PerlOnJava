use strict;
use warnings;
use B ();
use Test::More;
use Scalar::Util qw(weaken);

our $weak_source;
our @events;

{
    package Issue2043::DuplicateOwnerSource;
    sub DESTROY { push @main::events, 'source-destroyed' }

    package Issue2043::DuplicateOwnerAggregate;
    sub DESTROY { }
}

sub dies_after_duplicate_aggregate_release {
    my ($kind) = @_;
    @events = ();

    my $source = bless {}, 'Issue2043::DuplicateOwnerSource';
    $weak_source = $source;
    weaken($weak_source);

    my $shared = $kind eq 'array'
        ? [ $source, $source ]
        : { first => $source, second => $source };
    bless $shared, 'Issue2043::DuplicateOwnerAggregate';
    my $root = { nested => $shared };
    my $before = B::svref_2object($source)->REFCNT;

    undef $root;
    undef $shared;
    my $after = B::svref_2object($source)->REFCNT;
    my $weak_alive_after_root = defined $weak_source;
    undef $source;

    return [$before - $after, $weak_alive_after_root,
        !defined($weak_source), scalar @events];
}

sub dies_after_nested_duplicate_aggregate_release {
    my ($kind) = @_;
    @events = ();

    my $source = bless {}, 'Issue2043::DuplicateOwnerSource';
    $weak_source = $source;
    weaken($weak_source);

    my $root = $kind eq 'array'
        ? { children => [ $source, $source ] }
        : [ { first => $source, second => $source } ];
    my $before = B::svref_2object($source)->REFCNT;

    undef $root;
    my $after = B::svref_2object($source)->REFCNT;
    my $weak_alive_after_root = defined $weak_source;
    undef $source;

    return [$before - $after, $weak_alive_after_root,
        !defined($weak_source), scalar @events];
}

sub dies_after_sibling_duplicate_aggregate_release {
    @events = ();

    my $source = bless {}, 'Issue2043::DuplicateOwnerSource';
    $weak_source = $source;
    weaken($weak_source);

    my $root = [ { source => $source }, { source => $source } ];
    my $before = B::svref_2object($source)->REFCNT;

    undef $root;
    my $after = B::svref_2object($source)->REFCNT;
    my $weak_alive_after_root = defined $weak_source;
    undef $source;

    return [$before - $after, $weak_alive_after_root,
        !defined($weak_source), scalar @events];
}

my ($array_owner_delta, $array_weak_alive, $array_released, $array_destroy_count) =
    @{ dies_after_duplicate_aggregate_release('array') };
is($array_owner_delta, 2, 'array cleanup releases both duplicate owner counts');
ok($array_weak_alive, 'array cleanup leaves the lexical source alive');
ok($array_released, 'array cleanup releases the final source owner');
is($array_destroy_count, 1, 'array duplicate owner releases fire DESTROY once');

my ($hash_owner_delta, $hash_weak_alive, $hash_released, $hash_destroy_count) =
    @{ dies_after_duplicate_aggregate_release('hash') };
is($hash_owner_delta, 2, 'hash cleanup releases both duplicate owner counts');
ok($hash_weak_alive, 'hash cleanup leaves the lexical source alive');
ok($hash_released, 'hash cleanup releases the final source owner');
is($hash_destroy_count, 1, 'hash duplicate owner releases fire DESTROY once');

my ($nested_array_delta, $nested_array_alive, $nested_array_released,
        $nested_array_destroy_count) =
    @{ dies_after_nested_duplicate_aggregate_release('array') };
is($nested_array_delta, 2,
    'nested array cleanup releases both duplicate owner counts');
ok($nested_array_alive, 'nested array cleanup leaves the lexical source alive');
ok($nested_array_released, 'nested array cleanup releases the final source owner');
is($nested_array_destroy_count, 1,
    'nested array duplicate owner releases fire DESTROY once');

my ($nested_hash_delta, $nested_hash_alive, $nested_hash_released,
        $nested_hash_destroy_count) =
    @{ dies_after_nested_duplicate_aggregate_release('hash') };
is($nested_hash_delta, 2,
    'nested hash cleanup releases both duplicate owner counts');
ok($nested_hash_alive, 'nested hash cleanup leaves the lexical source alive');
ok($nested_hash_released, 'nested hash cleanup releases the final source owner');
is($nested_hash_destroy_count, 1,
    'nested hash duplicate owner releases fire DESTROY once');

my ($sibling_delta, $sibling_alive, $sibling_released, $sibling_destroy_count) =
    @{ dies_after_sibling_duplicate_aggregate_release() };
is($sibling_delta, 2,
    'sibling nested aggregate cleanup releases both duplicate owner counts');
ok($sibling_alive, 'sibling nested cleanup leaves the lexical source alive');
ok($sibling_released, 'sibling nested cleanup releases the final source owner');
is($sibling_destroy_count, 1,
    'sibling nested duplicate owner releases fire DESTROY once');

done_testing();
