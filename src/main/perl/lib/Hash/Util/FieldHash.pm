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

# id() returns the reference address (like Scalar::Util::refaddr)
sub id ($) {
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

sub TIEHASH {
    my ($class) = @_;
    return bless {
        values => {},
        refs   => {},
        order  => [],
        iter   => [],
    }, $class;
}

sub _id_for_key {
    my ($key) = @_;
    die "Hash::Util::FieldHash keys must be references\n" unless ref $key;

    my $id = Scalar::Util::refaddr($key);
    die "Hash::Util::FieldHash keys must be references\n" unless defined $id;

    return $id;
}

sub _remember_key {
    my ($self, $id, $key) = @_;

    if (!exists $self->{refs}{$id}) {
        push @{ $self->{order} }, $id;
    }
    $self->{refs}{$id} = $key;
    eval { Scalar::Util::weaken($self->{refs}{$id}) };
    return;
}

sub _prune {
    my ($self) = @_;

    for my $id (keys %{ $self->{refs} }) {
        next if defined $self->{refs}{$id};
        delete $self->{refs}{$id};
        delete $self->{values}{$id};
    }

    @{ $self->{order} } = grep { exists $self->{refs}{$_} } @{ $self->{order} };
    return;
}

sub STORE {
    my ($self, $key, $value) = @_;
    my $id = _id_for_key($key);

    $self->_prune;
    $self->_remember_key($id, $key);
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
    return delete $self->{values}{$id};
}

sub CLEAR {
    my ($self) = @_;

    %{ $self->{values} } = ();
    %{ $self->{refs} } = ();
    @{ $self->{order} } = ();
    @{ $self->{iter} } = ();
    return;
}

sub FIRSTKEY {
    my ($self) = @_;

    $self->_prune;
    @{ $self->{iter} } = grep { exists $self->{refs}{$_} } @{ $self->{order} };
    return $self->NEXTKEY;
}

sub NEXTKEY {
    my ($self) = @_;

    $self->_prune;
    while (@{ $self->{iter} }) {
        my $id = shift @{ $self->{iter} };
        next unless exists $self->{refs}{$id};
        return $self->{refs}{$id};
    }
    return undef;
}

sub SCALAR {
    my ($self) = @_;

    $self->_prune;
    return scalar keys %{ $self->{values} };
}

1;
