package Hash::Util::FieldHash;
use strict;
use warnings;
our $VERSION = '1.26';

use Exporter 'import';
our @EXPORT_OK = qw(
    fieldhash fieldhashes
    idhash idhashes
    id id_2obj register
);
our %EXPORT_TAGS = (all => \@EXPORT_OK);

use Scalar::Util ();

my %REGISTRY;

sub _tie_identity_hash {
    my ($hash) = @_;

    if (my $tied = tied %$hash) {
        if (UNIVERSAL::isa($tied, 'Hash::Util::FieldHash::_Tie')) {
            $tied->CLEAR;
            return $hash;
        }
    }

    %$hash = ();
    tie %$hash, 'Hash::Util::FieldHash::_Tie';
    return $hash;
}

sub fieldhash (\%) { _tie_identity_hash($_[0]) }

sub fieldhashes {
    for (@_) {
        fieldhash(%$_);
    }
    return @_;
}

# idhash is the same concept but without GC magic even in standard Perl.
# PerlOnJava uses the same tied identity hash for both forms; weak-key cleanup
# is opportunistic and happens on access.
sub idhash (\%) { _tie_identity_hash($_[0]) }

sub idhashes {
    for (@_) {
        idhash(%$_);
    }
    return @_;
}

# id() returns scalar values unchanged and reference addresses for references.
sub id ($) {
    return $_[0] unless ref $_[0];

    require Scalar::Util;
    return Scalar::Util::refaddr($_[0]);
}

sub id_2obj ($) {
    my $obj = $REGISTRY{$_[0]};
    return defined $obj ? $obj : undef;
}

sub register {
    my ($obj, @hashes) = @_;
    if (ref $obj) {
        my $id = id($obj);
        $REGISTRY{$id} = $obj;
        eval { Scalar::Util::weaken($REGISTRY{$id}) };
    }
    return $obj;
}

1;

package Hash::Util::FieldHash::_Tie;

use strict;
use warnings;
use Scalar::Util ();

our @TIES;

sub TIEHASH {
    my ($class) = @_;
    my $self = bless {
        values => {},
        refs   => {},
        plain  => {},
        order  => [],
        iter   => [],
    }, $class;
    push @TIES, $self;
    Scalar::Util::weaken($TIES[-1]);
    return $self;
}

# Numeric keys are refaddrs, which change when ithreads clone their referents.
# Re-key each live fieldhash entry against the cloned weak key during CLONE.
sub CLONE {
    my @live;
    for my $tie (grep { defined } @TIES) {
        my (%values, %refs, %plain);
        my @order;
        for my $old_id (@{ $tie->{order} }) {
            next unless exists $tie->{values}{$old_id};
            if (exists $tie->{plain}{$old_id}) {
                $values{$old_id} = $tie->{values}{$old_id};
                $plain{$old_id} = $tie->{plain}{$old_id};
                push @order, $old_id;
                next;
            }
            my $key = $tie->{refs}{$old_id};
            next unless defined $key;
            my $new_id = _id_for_key($key);
            $refs{$new_id} = $key;
            $values{$new_id} = $tie->{values}{$old_id};
            push @order, $new_id;
        }
        $tie->{refs} = \%refs;
        $tie->{plain} = \%plain;
        $tie->{values} = \%values;
        @{ $tie->{order} } = @order;
        @{ $tie->{iter} } = ();
        push @live, $tie;
    }
    @TIES = @live;
    Scalar::Util::weaken($_) for @TIES;
}

sub _id_for_key {
    my ($key) = @_;
    if (ref $key) {
        my $id = Scalar::Util::refaddr($key);
        return 'R:' . $id if defined $id;
    }
    my $string_key = defined($key) ? "$key" : '';
    return 'S:' . length($string_key) . ':' . $string_key;
}

sub _remember_key {
    my ($self, $id, $key) = @_;

    if (!exists $self->{values}{$id}) {
        push @{ $self->{order} }, $id;
    }
    if (ref $key) {
        $self->{refs}{$id} = $key;
        eval { Scalar::Util::weaken($self->{refs}{$id}) };
    } else {
        $self->{plain}{$id} = defined($key) ? "$key" : '';
    }
    return;
}

sub _prune {
    my ($self) = @_;

    for my $id (keys %{ $self->{refs} }) {
        next if defined $self->{refs}{$id};
        delete $self->{refs}{$id};
        delete $self->{values}{$id};
    }

    @{ $self->{order} } = grep { exists $self->{values}{$_} } @{ $self->{order} };
    return;
}

sub STORE {
    my ($self, $key, $value) = @_;
    my $id = _id_for_key($key);

    $self->_prune;
    $self->_remember_key($id, $key) unless exists $self->{values}{$id};
    if (!ref $key) {
        $self->{plain}{$id} = defined($key) ? "$key" : '';
    } elsif (!exists $self->{refs}{$id}) {
        $self->{refs}{$id} = $key;
        eval { Scalar::Util::weaken($self->{refs}{$id}) };
    }
    $self->{values}{$id} = $value;
    return $value;
}

sub FETCH {
    my ($self, $key) = @_;
    my $id = _id_for_key($key);

    $self->_prune;
    return exists $self->{values}{$id} ? $self->{values}{$id} : undef;
}

sub EXISTS {
    my ($self, $key) = @_;
    my $id = _id_for_key($key);

    $self->_prune;
    return exists $self->{values}{$id};
}

sub DELETE {
    my ($self, $key) = @_;
    my $id = _id_for_key($key);

    $self->_prune;
    delete $self->{refs}{$id};
    delete $self->{plain}{$id};
    return delete $self->{values}{$id};
}

sub CLEAR {
    my ($self) = @_;

    %{ $self->{values} } = ();
    %{ $self->{refs} } = ();
    %{ $self->{plain} } = ();
    @{ $self->{order} } = ();
    @{ $self->{iter} } = ();
    return;
}

sub FIRSTKEY {
    my ($self) = @_;

    $self->_prune;
    @{ $self->{iter} } = grep { exists $self->{values}{$_} } @{ $self->{order} };
    return $self->NEXTKEY;
}

sub NEXTKEY {
    my ($self) = @_;

    $self->_prune;
    while (@{ $self->{iter} }) {
        my $id = shift @{ $self->{iter} };
        next unless exists $self->{values}{$id};
        return $self->{refs}{$id} if exists $self->{refs}{$id};
        return $self->{plain}{$id} if exists $self->{plain}{$id};
    }
    return undef;
}

sub SCALAR {
    my ($self) = @_;

    $self->_prune;
    return scalar keys %{ $self->{values} };
}

1;
