use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

# =============================================================================
# cmop_pattern.t — Reproducer for the metaclass refCount underflow.
#
# Pattern: %METAS-style cache + method-chain temps + weakened back-refs.
# This mirrors the exact CMOP bootstrap shape that triggers the
# walker-gate fallback. Without the gate, refCount underflows and DESTROY
# fires prematurely.
# =============================================================================

# T1: storage via method that stores AND returns ($METAS{...} ||= ...)
{
    package T1::Class;
    our %METAS;
    sub initialize {
        my ($class, $name) = @_;
        return $METAS{$name} ||= bless { name => $name }, $class;
    }
    sub add_attribute {
        my ($self, $attr) = @_;
        push @{ $self->{attrs} ||= [] }, $attr;
        $attr->{associated_class} = $self;
        Scalar::Util::weaken($attr->{associated_class});
        # The defining check: associated_class must still be alive
        # immediately after weaken, *and* during the next statement.
        unless (defined $attr->{associated_class}) {
            die "owner gone for $attr->{name}!\n";
        }
        return $self;
    }
    sub DESTROY { $main::T1_DESTROYED++ }

    package T1::Mixin;
    sub meta {
        T1::Class->initialize(ref($_[0]) || $_[0]);
    }

    package T1::Attr;
    sub new { bless { name => $_[1] }, $_[0] }

    package T1::Target;
    our @ISA = ('T1::Mixin');

    package main;
    $main::T1_DESTROYED = 0;

    # CMOP-style: 3 separate top-level statements that go
    #   Class->meta->add_attribute(Attr->new(...))
    # The metaclass returned from ->meta is a temporary; its only
    # persistent owner is %METAS.
    eval {
        T1::Target->meta->add_attribute(T1::Attr->new('one'));
        T1::Target->meta->add_attribute(T1::Attr->new('two'));
        T1::Target->meta->add_attribute(T1::Attr->new('three'));
        T1::Target->meta->add_attribute(T1::Attr->new('four'));
        T1::Target->meta->add_attribute(T1::Attr->new('five'));
    };
    is($@, '',
       'T1: associated_class survives across method-chain temps');
    is($main::T1_DESTROYED, 0,
       'T1: meta NOT destroyed during 5-attribute bootstrap');

    # All weakened back-refs must still be defined
    my $meta = T1::Target->meta;
    my @names = map { $_->{name} } @{ $meta->{attrs} || [] };
    is_deeply(\@names, [qw(one two three four five)],
              'T1: all 5 attrs registered');
    for my $a (@{ $meta->{attrs} || [] }) {
        ok(defined $a->{associated_class},
           "T1: attr '$a->{name}' associated_class still defined");
    }
}

# T2: shape with TWO METAS hashes (separate caches) and cross-refs
{
    package T2::Cache::A;
    our %M;
    sub get_or_create {
        $M{$_[0]} ||= bless { name => $_[0], links => [] }, 'T2::A';
    }
    package T2::Cache::B;
    our %M;
    sub get_or_create {
        $M{$_[0]} ||= bless { name => $_[0], links => [] }, 'T2::B';
    }

    package T2::A;
    sub link {
        my ($self, $b) = @_;
        $self->{partner} = $b;
        Scalar::Util::weaken($self->{partner});
        die "A's partner gone!\n" unless defined $self->{partner};
    }
    sub DESTROY { $main::T2_A_DESTROYED++ }

    package T2::B;
    sub link {
        my ($self, $a) = @_;
        $self->{partner} = $a;
        Scalar::Util::weaken($self->{partner});
        die "B's partner gone!\n" unless defined $self->{partner};
    }
    sub DESTROY { $main::T2_B_DESTROYED++ }

    package main;
    $main::T2_A_DESTROYED = 0;
    $main::T2_B_DESTROYED = 0;

    eval {
        for my $name (qw(X Y Z W V)) {
            T2::Cache::A::get_or_create($name)
              ->link(T2::Cache::B::get_or_create($name));
            T2::Cache::B::get_or_create($name)
              ->link(T2::Cache::A::get_or_create($name));
        }
    };
    is($@, '', 'T2: cross-linked partners survive method-chain temps');
    is($main::T2_A_DESTROYED, 0, 'T2: no A DESTROY');
    is($main::T2_B_DESTROYED, 0, 'T2: no B DESTROY');
}

done_testing();
