package Class::MOP::Instance;

# PerlOnJava skeleton stub for Class::MOP::Instance. The Moose-as-Moo
# shim doesn't have a separate instance metaclass; this exists only so
# `require Class::MOP::Instance` succeeds and ->new returns a hashref-shaped
# object with the methods upstream tests inspect.

use strict;
use warnings;

our $VERSION = '2.4000';

sub new {
    my ($class, %args) = @_;
    return bless { %args }, $class;
}

sub create_instance {
    my ($self, %args) = @_;
    return bless { %args }, ($self->{associated_class} || 'main');
}

sub associated_metaclass { $_[0]->{associated_metaclass} }
sub get_all_slots        { () }
sub get_all_attributes   { () }

# Slot accessors: trivial hashref get/set.
sub get_slot_value      { my (undef,$o,$s) = @_; $o->{$s} }
sub set_slot_value      { my (undef,$o,$s,$v) = @_; $o->{$s} = $v }
sub deinitialize_slot   { my (undef,$o,$s) = @_; delete $o->{$s} }
sub is_slot_initialized { my (undef,$o,$s) = @_; exists $o->{$s} }
sub initialize_slot     { my (undef,$o,$s) = @_; $o->{$s} = undef }
sub weaken_slot_value   {
    my (undef,$o,$s) = @_;
    require Scalar::Util;
    Scalar::Util::weaken($o->{$s});
}

1;
__END__
=head1 NAME
Class::MOP::Instance - PerlOnJava skeleton stub.
=cut
