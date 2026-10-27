use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(weaken);

my @weak_nodes;
my $root = bless {}, 'Local::AggregateOwnerNode';
push @weak_nodes, $root;
weaken($weak_nodes[-1]);

my @children;
for (1 .. 3) {
    my $child = bless {}, 'Local::AggregateOwnerNode';
    push @weak_nodes, $child;
    weaken($weak_nodes[-1]);
    push @children, $child;
}

$root->{children} = \@children;
is(scalar(grep { defined $_ } @weak_nodes), 4,
    'the lexical array and parent hash keep all child values alive');

$root = undef;
@children = ();
is(scalar(grep { defined $_ } @weak_nodes), 0,
    'dropping both strong owners releases all children');
