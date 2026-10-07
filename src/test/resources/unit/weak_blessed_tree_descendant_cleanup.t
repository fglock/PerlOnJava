use strict;
use warnings;
use Test::More tests => 3;
use Scalar::Util qw(isweak weaken);

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

{
    package HTML::Element;

    sub new { bless {}, shift }

    sub append {
        my ($self, $child) = @_;
        $self->{child} = $child;
        $child->{_parent} = $self;
        Scalar::Util::weaken($child->{_parent});
        return $self;
    }
}

{
    my $tree = HTML::Element->new;
    $tree->append(HTML::Element->new);
    is(live_object_count(), 2, 'parent owns a child observed through a weak list');
    ok(isweak($OBJECTS[1]{_parent}), 'child points back to parent weakly');
    $tree = undef;
}

is(live_object_count(), 0, 'dropping the tree releases its weakly observed descendants');
