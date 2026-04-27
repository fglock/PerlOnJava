package Moose::Meta::TypeConstraint;

# PerlOnJava skeleton base class for type constraints. The shim's actual
# type-constraint stubs live in Moose::Util::TypeConstraints::_Stub
# (now @ISA-set to inherit from this class so isa_ok($t,
# 'Moose::Meta::TypeConstraint') passes).

use strict;
use warnings;

our $VERSION = '2.4000';

sub new {
    my ($class, %opts) = @_;
    $opts{constraint} ||= sub { 1 };
    return bless { %opts }, $class;
}

sub name              { $_[0]->{name} }
sub parent            { $_[0]->{parent} }
sub has_parent        { defined $_[0]->{parent} ? 1 : 0 }
sub constraint        { $_[0]->{constraint} }
sub has_constraint    { defined $_[0]->{constraint} ? 1 : 0 }
sub message           { $_[0]->{message} }
sub has_message       { defined $_[0]->{message} ? 1 : 0 }
sub coercion          { $_[0]->{coercion} }
sub has_coercion      { defined $_[0]->{coercion} ? 1 : 0 }
sub can_be_inlined    { 0 }
sub inline_environment { {} }
sub _inline_check     { 'do { 1 }' }
sub _compile_type     { $_[0]->{constraint} }
sub _compile_subtype  { $_[0]->{constraint} }

sub check {
    my ($self, $value) = @_;
    my $c = $self->{constraint};
    return $c ? $c->($value) : 1;
}

sub validate {
    my ($self, $value) = @_;
    return undef if $self->check($value);
    return "Validation failed for '" . ($self->name // 'Anon') . "'";
}

sub assert_valid {
    my ($self, $value) = @_;
    return 1 if $self->check($value);
    require Carp;
    Carp::croak($self->validate($value));
}

sub equals {
    my ($self, $other) = @_;
    my $a = ref $self  ? $self->name  : $self;
    my $b = ref $other ? $other->name : $other;
    return defined $a && defined $b && $a eq $b;
}

sub is_subtype_of {
    my ($self, $name) = @_;
    my $p = $self->{parent};
    while (defined $p) {
        return 1 if $p eq $name;
        # Look up in standard registry if available.
        my $pp;
        if (defined &Moose::Util::TypeConstraints::find_type_constraint) {
            $pp = Moose::Util::TypeConstraints::find_type_constraint($p);
        }
        $p = $pp ? $pp->{parent} : undef;
    }
    return 0;
}

sub is_a_type_of {
    my ($self, $name) = @_;
    return 1 if $self->equals($name);
    return $self->is_subtype_of($name);
}

1;
__END__
=head1 NAME
Moose::Meta::TypeConstraint - PerlOnJava skeleton stub.
=cut
