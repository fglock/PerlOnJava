use strict;
use warnings;
use Scalar::Util qw(isweak weaken);
use Test::More;

our @weak_nodes;
our $destroyed = 0;

{
    package OwnershipTreeNode;

    sub new {
        my ($class, $tag) = @_;
        my $node = bless { tag => $tag, children => [] }, $class;
        push @main::weak_nodes, $node;
        Scalar::Util::weaken($main::weak_nodes[-1]);
        return $node;
    }

    sub DESTROY {
        ++$main::destroyed;
    }
}

sub build_tree {
    my ($spec) = @_;
    my (@spec_stack, $builder, $node);
    $builder = sub {
        my ($node_spec) = @_;
        push @spec_stack, $node_spec;
        my @children;
        for my $child_spec (@{ $node_spec->[1] }) {
            push @children, $builder->($child_spec);
        }

        $node = OwnershipTreeNode->new($node_spec->[0]);
        $node->{children} = \@children;
        for my $child (@children) {
            $child->{parent} = $node;
            weaken($child->{parent});
        }
        pop @spec_stack;
        return $node;
    };

    my $tree = $builder->($spec);
    undef $builder;
    return $tree;
}

my $tree = build_tree([
    html => [
        [head => [[title => []]]],
        [body => [[p => []]]],
    ],
]);
my $body = $tree->{children}[1];

is(scalar(grep { defined $_ } @weak_nodes), 5,
    'strong child slots keep the tree nodes alive');
ok(isweak($body->{parent}), 'child parent links are weak while the tree is alive');
is($body->{parent}{tag}, 'html', 'the body has a live parent before root release');

$tree = undef;

is(scalar(grep { defined $_ } @weak_nodes), 2,
    'dropping the root releases non-escaped subtrees');
$body = undef;
is(scalar(grep { defined $_ } @weak_nodes), 0,
    'dropping the escaped subtree releases its nodes');
is($destroyed, 5, 'each tree node is destroyed once');

done_testing;
