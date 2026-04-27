package Moose::Meta::TypeConstraint::Enum;

# PerlOnJava skeleton stub for parameterized Enum type. Inherits from
# Moose::Meta::TypeConstraint.

use strict;
use warnings;
our $VERSION = '2.4000';

require Moose::Meta::TypeConstraint;
our @ISA = ('Moose::Meta::TypeConstraint');

sub new {
    my ($class, %opts) = @_;
    my $values = $opts{values} || [];
    my %ok = map { $_ => 1 } @$values;
    $opts{constraint} ||= sub { defined $_[0] && exists $ok{$_[0]} };
    return bless { %opts }, $class;
}

sub values { $_[0]->{values} || [] }

1;
__END__
=head1 NAME
Moose::Meta::TypeConstraint::Enum - PerlOnJava skeleton stub.
=cut
