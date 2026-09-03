use strict;
use warnings;
use Test::More;
use Scalar::Util qw(refaddr weaken);

# Regression for issue #1224. PPIx::Regexp::Structure constructs an object by
# separating bracket elements from @args, forwarding the remaining children to
# SUPER::__new(), and then installing the bracket arrays. Every element has a
# weak parent-map entry that its DESTROY method removes.
{
    package Issue1224::Element;
    my %parent;

    sub new { bless {}, shift }

    sub _parent {
        my ($self, @arg) = @_;
        my $address = Scalar::Util::refaddr($self);
        if (@arg) {
            my $value = shift @arg;
            if (defined $value) {
                Scalar::Util::weaken($parent{$address} = $value);
            } else {
                delete $parent{$address};
            }
        }
        return $parent{$address};
    }

    sub parent_count { scalar keys %parent }

    sub DESTROY {
        $_[0]->_parent(undef);
    }
}

{
    package Issue1224::Node;
    our @ISA = 'Issue1224::Element';

    sub __new {
        my ($class, @children) = @_;
        my $self = bless { children => \@children }, ref $class || $class;
        $_->_parent($self) for @children;
        return $self;
    }
}

{
    package Issue1224::Structure;
    our @ISA = 'Issue1224::Node';

    sub __new {
        my ($class, @args) = @_;
        my %bracket;
        $bracket{finish} = [@args ? pop @args : ()];
        $bracket{start} = [@args ? shift @args : ()];
        $bracket{type} = [];

        my $self = $class->SUPER::__new(@args);
        for my $key (qw(start type finish)) {
            $self->{$key} = [];
            for my $value (@{ $bracket{$key} }) {
                next if !defined $value;
                push @{ $self->{$key} }, $value;
                $value->_parent($self);
            }
        }
        return $self;
    }
}

my $structure = Issue1224::Structure->__new(
    Issue1224::Element->new,
    Issue1224::Element->new,
    Issue1224::Element->new,
);

is(Issue1224::Element->parent_count, 3,
    'constructor registers start, child, and finish parent entries');

undef $structure;

is(Issue1224::Element->parent_count, 0,
    'releasing the structure destroys all parent-map children');

done_testing();
