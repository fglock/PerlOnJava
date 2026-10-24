use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

our @WEAK_NODES;
our $DESTROYED = 0;

{
    package Issue1649ArrayArgumentNode;

    sub new {
        my ($class) = @_;
        my $node = bless {}, $class;
        push @main::WEAK_NODES, $node;
        Scalar::Util::weaken($main::WEAK_NODES[-1]);
        return $node;
    }

    sub DESTROY { ++$main::DESTROYED }
}

sub build_node {
    my $input = ['array argument'];
    my ($builder, $node);
    $builder = sub {
        my $argument = $_[0];
        $node = Issue1649ArrayArgumentNode->new;
        return $node;
    };
    $node = $builder->($input);
    undef $builder;
    return $node;
}

my $node = build_node();
$node = undef;

is(scalar(grep { defined $_ } @WEAK_NODES), 0,
    'array argument and released closure do not retain the returned object');
is($DESTROYED, 1, 'returned object is destroyed once');

done_testing;
