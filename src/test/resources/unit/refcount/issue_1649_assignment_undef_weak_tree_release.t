use strict;
use warnings;
use Scalar::Util qw(isweak weaken);
use Test::More;

our (@WEAK_NODES, $DESTROYED);
$DESTROYED = 0;

{
    package Issue1649ExplicitUndefNode;

    sub new {
        my ($class) = @_;
        my $node = bless { children => [] }, $class;
        push @main::WEAK_NODES, $node;
        Scalar::Util::weaken($main::WEAK_NODES[-1]);
        return $node;
    }

    sub DESTROY { ++$main::DESTROYED }
}

sub make_tree {
    my $root = Issue1649ExplicitUndefNode->new;
    my $child = Issue1649ExplicitUndefNode->new;
    push @{ $root->{children} }, $child;
    $child->{parent} = $root;
    weaken($child->{parent});
    return $root;
}

my $root = make_tree();
is(scalar(grep { defined $_ } @WEAK_NODES), 2,
    'the root owns its child through the children array');
ok(isweak($WEAK_NODES[1]{parent}), 'the child parent link is weak');

$root = undef;
is(scalar(grep { defined $_ } @WEAK_NODES), 0,
    'assigning undef to the last root releases the weakly linked tree');
is($DESTROYED, 2, 'both nodes are destroyed once');

done_testing;
