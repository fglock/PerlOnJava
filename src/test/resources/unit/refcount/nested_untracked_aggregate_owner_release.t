use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

our @DESTROYED;
our $RESCUED;
{
    package NestedUntrackedAggregateOwnerRelease::Target;
    sub DESTROY {
        push @main::DESTROYED, $_[0]{name};
        $main::RESCUED = $_[0] if $_[0]{rescue};
    }
}

sub nested_owner_graph {
    my ($name, $rescue) = @_;
    my $target = bless { name => $name, rescue => $rescue },
        'NestedUntrackedAggregateOwnerRelease::Target';
    my $weak = $target;
    weaken($weak);
    my $leaf = { target => $target };
    my $level_two = [$leaf];
    my $level_one = { nested => $level_two };
    my $outer = [$level_one];
    return ($outer, $level_one, $weak);
}

{
    my ($outer, $shared, $weak) = nested_owner_graph('unshared');
    weaken($weak);
    undef $shared;
    undef $outer;
    ok(!defined($weak),
        'releasing mixed nested untracked aggregates releases their deepest target');
}

{
    my ($outer, $shared, $weak) = nested_owner_graph('shared');
    weaken($weak);
    undef $outer;
    ok(defined($weak), 'a surviving shared nested aggregate retains its target');
    undef $shared;
    ok(!defined($weak), 'releasing the final nested aggregate owner releases its target');
}

{
    my ($outer, $shared, $weak) = nested_owner_graph('rescued', 1);
    weaken($weak);
    undef $outer;
    undef $shared;
    ok(defined($weak), 'nested cleanup preserves a target rescued by DESTROY');
    $RESCUED->{rescue} = 0;
    undef $RESCUED;
    is(scalar @DESTROYED, 4, 'dropping the rescued target invokes its final DESTROY');
}

is_deeply(
    \@DESTROYED,
    [qw(unshared shared rescued rescued)],
    'nested releases and rescue dispatch match Perl destructor order',
);
done_testing();
