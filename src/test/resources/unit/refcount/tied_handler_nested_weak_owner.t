use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

{
    package TiedHandlerNestedWeakOwner::Probe;

    our $DESTROY_COUNT = 0;

    sub new { bless {}, shift }

    sub DESTROY { $DESTROY_COUNT++ }

    package TiedHandlerNestedWeakOwner::Tie;

    sub TIEHASH { bless { target => $_[1] }, $_[0] }

    sub FETCH { $_[0]->{target} }

    sub STORE { $_[0]->{target} = $_[2] }

    sub clear { delete $_[0]->{target} }
}

our $weak_target;

sub make_tied_hash_with_weak_target {
    my $target = TiedHandlerNestedWeakOwner::Probe->new;
    my %hash;
    tie %hash, 'TiedHandlerNestedWeakOwner::Tie', $target;
    $weak_target = $target;
    weaken($weak_target);
    return \%hash;
}

my $hash = make_tied_hash_with_weak_target();
my $tie = tied %{$hash};

ok(defined $weak_target, 'nested value remains alive through the tied handler');
is($TiedHandlerNestedWeakOwner::Probe::DESTROY_COUNT, 0,
    'nested value is not destroyed while the tied handler stores it');

$tie->clear;

ok(!defined $weak_target, 'removing the final nested handler value clears its weak observer');
is($TiedHandlerNestedWeakOwner::Probe::DESTROY_COUNT, 1,
    'nested value is destroyed exactly once after its final handler slot leaves');

done_testing();
