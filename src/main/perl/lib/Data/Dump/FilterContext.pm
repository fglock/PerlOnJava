package Data::Dump::FilterContext;

use strict;
use warnings;

sub new {
    my ($class, $object, $object_class, $reftype, $is_ref, $parent_class, $parent_index, $index) = @_;
    return bless {
        object => $object,
        class => $is_ref && $object_class,
        reftype => $reftype,
        is_ref => $is_ref,
        pclass => $parent_class,
        pidx => $parent_index,
        idx => $index || [],
    }, $class;
}

sub object_ref {
    my ($self) = @_;
    return $self->{object};
}

sub class {
    my ($self) = @_;
    return $self->{class} || '';
}

*is_blessed = \&class;

sub reftype {
    my ($self) = @_;
    return $self->{reftype};
}

sub is_scalar {
    my ($self) = @_;
    return $self->{reftype} eq 'SCALAR';
}

sub is_array {
    my ($self) = @_;
    return $self->{reftype} eq 'ARRAY';
}

sub is_hash {
    my ($self) = @_;
    return $self->{reftype} eq 'HASH';
}

sub is_code {
    my ($self) = @_;
    return $self->{reftype} eq 'CODE';
}

sub is_ref {
    my ($self) = @_;
    return $self->{is_ref};
}

sub container_class {
    my ($self) = @_;
    return $self->{pclass} || '';
}

sub container_self {
    my ($self) = @_;
    return '' unless $self->{pclass};
    my $idx = $self->{idx};
    my $pidx = $self->{pidx};
    return Data::Dump::fullname('self', [@$idx[$pidx .. (@$idx - 1)]]);
}

sub expr {
    my ($self, $top) = @_;
    $top ||= 'var';
    $top =~ s/^\$//;
    return Data::Dump::fullname($top, $self->{idx});
}

sub object_isa {
    my ($self, $class) = @_;
    return $self->{class} && $self->{class}->isa($class);
}

sub container_isa {
    my ($self, $class) = @_;
    return $self->{pclass} && $self->{pclass}->isa($class);
}

sub depth {
    my ($self) = @_;
    return scalar @{$self->{idx}};
}

1;
