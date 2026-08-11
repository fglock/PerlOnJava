use strict;
use warnings;
use Test::More;
use Scalar::Util qw(isweak weaken);

{
    package Local::TreeHolder;

    sub new {
        return bless {}, shift;
    }

    sub parse {
        my $self = shift;
        my $current = my $root = ['root'];
        push @$current, my $parent = ['tag', 'body', {}, $current];
        Scalar::Util::weaken($parent->[3]);
        $current = $parent;
        push @$current, my $child = ['tag', 'p', {}, $current];
        Scalar::Util::weaken($child->[3]);
        $self->{tree} = $root;
        return $self;
    }

    sub tree { return $_[0]{tree} }
}

sub parse_tree {
    return Local::TreeHolder->new->parse->tree;
}

my $tree = parse_tree();

ok(defined($tree), 'reference returned from temporary invocant remains live');
ok(defined($tree->[1][3]), 'weak child-to-root edge remains live');
ok(isweak($tree->[1][3]), 'child-to-root edge remains weak');
is($tree->[1][3], $tree, 'child-to-root edge points to returned root');
ok(defined($tree->[1][4][3]), 'descendant weak parent remains live');
is($tree->[1][4][3], $tree->[1],
    'descendant weak parent points inside returned graph');

sub parsed_nodes {
    my $parsed = parse_tree();
    my @nodes = @$parsed[1 .. $#$parsed];
    return shift() ? [grep { $_->[0] eq 'tag' } @nodes] : \@nodes;
}

my @destination = ('root');
{
    my $nodes = parsed_nodes();
    for my $node (@$nodes) {
        $node->[3] = \@destination;
        weaken($node->[3]);
    }
    splice @destination, 1, 0, @$nodes;
}

ok(defined($destination[1][4][3]),
    'nested parse/slice return keeps descendant parent live');
is($destination[1][4][3], $destination[1],
    'nested parse/slice descendant points to inserted parent');

done_testing;
