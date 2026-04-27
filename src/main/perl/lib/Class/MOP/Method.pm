package Class::MOP::Method;

# PerlOnJava skeleton stub for Class::MOP::Method.
#
# Just enough surface that `Class::MOP::Method->wrap(body => $code,
# name => $n, package_name => $pkg)` returns an object responding to
# ->body / ->name / ->package_name / ->fully_qualified_name. Used by
# Moose::_FakeMeta->get_method.

use strict;
use warnings;

our $VERSION = '2.4000';

sub wrap {
    my ($class, %args) = @_;
    return bless { %args }, $class;
}

sub new { my ($class, %args) = @_; return bless { %args }, $class; }

sub body              { $_[0]->{body} }
sub name              { $_[0]->{name} }
sub package_name      { $_[0]->{package_name} }
sub associated_metaclass { undef }
sub original_method   { $_[0] }
sub fully_qualified_name {
    my $self = shift;
    return defined $self->{package_name} && defined $self->{name}
        ? "$self->{package_name}::$self->{name}"
        : $self->{name};
}
sub is_stub           { 0 }

1;
__END__
=head1 NAME
Class::MOP::Method - PerlOnJava skeleton stub.
=cut
