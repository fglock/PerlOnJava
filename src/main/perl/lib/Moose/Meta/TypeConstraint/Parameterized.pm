package Moose::Meta::TypeConstraint::Parameterized;

# PerlOnJava skeleton stub. Real type-constraint parameterization
# (`ArrayRef[Foo]` etc.) is not implemented under the shim — the Moose
# shim's _make_isa_check translates the string form to a runtime check
# already.

use strict;
use warnings;

our $VERSION = '2.4000';

sub new {
    my ($class, %opts) = @_;
    return bless { %opts }, $class;
}

sub name              { $_[0]->{name} }
sub parent            { $_[0]->{parent} }
sub type_parameter    { $_[0]->{type_parameter} }
sub check             { 1 }
sub assert_valid      { 1 }

1;

__END__

=head1 NAME

Moose::Meta::TypeConstraint::Parameterized - PerlOnJava skeleton stub.

=cut
