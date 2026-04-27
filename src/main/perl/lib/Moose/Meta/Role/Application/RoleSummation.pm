package Moose::Meta::Role::Application::RoleSummation;

# PerlOnJava skeleton stub for the role-summation step of Moose's role
# composition. The shim delegates role composition to Moo::Role, which has
# its own summation logic; this module exists so `require X` succeeds.

use strict;
use warnings;

our $VERSION = '2.4000';

sub new {
    my ($class, %opts) = @_;
    return bless { %opts }, $class;
}

sub apply {
    my ($self, @args) = @_;
    return $self;
}

1;

__END__

=head1 NAME

Moose::Meta::Role::Application::RoleSummation - PerlOnJava skeleton stub.

=cut
