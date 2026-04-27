package Moose::Meta::Attribute;

# PerlOnJava skeleton stub for Moose::Meta::Attribute. Inherits from
# Class::MOP::Attribute so isa_ok($attr, 'Moose::Meta::Attribute') and
# isa_ok($attr, 'Class::MOP::Attribute') both pass.

use strict;
use warnings;

our $VERSION = '2.4000';

require Class::MOP::Attribute;
our @ISA = ('Class::MOP::Attribute');

sub new {
    my ($class, @args) = @_;
    return Class::MOP::Attribute::new($class, @args);
}

# Moose-specific accessors over the shared opts hash.
sub does           { my ($self, $role) = @_; my $r = $self->{does} || []; for my $x (ref $r ? @$r : ($r)) { return 1 if defined $x && $x eq $role } return 0 }
sub has_does       { exists $_[0]->{does} }
sub coerce         { $_[0]->{coerce} }
sub has_coerce     { $_[0]->{coerce} ? 1 : 0 }
sub trigger        { $_[0]->{trigger} }
sub has_trigger    { exists $_[0]->{trigger} }
sub handles        { $_[0]->{handles} }
sub has_handles    { exists $_[0]->{handles} }
sub documentation  { $_[0]->{documentation} }
sub has_documentation { exists $_[0]->{documentation} }
sub traits         { $_[0]->{traits} }
sub has_traits     { exists $_[0]->{traits} }
sub is_weak_ref    { $_[0]->{weak_ref} ? 1 : 0 }
sub should_coerce  { $_[0]->{coerce} ? 1 : 0 }
sub should_auto_deref { $_[0]->{auto_deref} ? 1 : 0 }

1;
__END__
=head1 NAME
Moose::Meta::Attribute - PerlOnJava skeleton stub.
=cut
