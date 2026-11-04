use strict;
use warnings;
use Test::More tests => 5;

our $detached_glob;
our @DESTROY_OBSERVED;

{
    package StashDeleteDetachedGlobUndefOrder::Probe;

    sub DESTROY {
        push @main::DESTROY_OBSERVED,
            defined($main::detached_glob) ? 'defined' : 'undef';
    }
}

my $object = bless {}, 'StashDeleteDetachedGlobUndefOrder::Probe';
{
    no strict 'refs';
    ${'StashDeleteDetachedGlobUndefOrder::value'} = $object;
}
undef $object;

$detached_glob = delete $StashDeleteDetachedGlobUndefOrder::{value};
ok(defined $detached_glob, 'stash deletion returns a defined detached glob');

undef $detached_glob;

is_deeply(
    \@DESTROY_OBSERVED,
    ['defined'],
    'component DESTROY runs while the scalar still holds the detached glob',
);
ok(!defined $detached_glob, 'the detached glob scalar is undef after undef returns');

my $replacement_object = bless {}, 'StashDeleteDetachedGlobUndefOrder::Probe';
{
    no strict 'refs';
    ${'StashDeleteDetachedGlobUndefOrder::value'} = $replacement_object;
}
undef $replacement_object;
$detached_glob = delete $StashDeleteDetachedGlobUndefOrder::{value};
$detached_glob = 'replacement';

is_deeply(
    \@DESTROY_OBSERVED,
    ['defined', 'defined'],
    'component DESTROY sees the old glob during scalar replacement too',
);
is($detached_glob, 'replacement', 'the replacement value is installed after DESTROY');

done_testing;
