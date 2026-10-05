use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(weaken);

our @OBJECTS;

BEGIN {
    no strict 'refs';
    *{'CORE::GLOBAL::bless'} = sub {
        my $reference = shift;
        my $class = @_ ? shift : scalar caller;
        my $object = CORE::bless($reference, $class);
        if ($object->isa('HTML::Element')) {
            push @OBJECTS, $object;
            weaken($OBJECTS[-1]);
        }
        return $object;
    };
}

sub live_object_count { scalar grep { defined $_ } @OBJECTS }

sub make_tree {
    my $node;
    my $builder;
    $builder = sub {
        my ($depth) = @_;
        if (!$depth) {
            $node = bless {}, 'HTML::Element';
            return $node;
        }
        my $child = $builder->($depth - 1);
        $node = bless {}, 'HTML::Element';
        $node->{child} = $child;
        $child->{parent} = $node;
        Scalar::Util::weaken($child->{parent});
        return $node;
    };

    my $tree = $builder->(3);
    undef $builder;
    return $tree;
}

{
    my $tree = make_tree();
    is(live_object_count(), 4, 'created four weakly observed nodes');
    $tree = undef;
}

is(live_object_count(), 0, 'recursive closure release frees the tree');
