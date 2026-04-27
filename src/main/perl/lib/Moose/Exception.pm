package Moose::Exception;

# PerlOnJava skeleton stub for Moose::Exception. Upstream Moose throws
# typed exception objects (Moose::Exception::AttributeMustHaveBeenLoaded
# etc.) via Throwable. The shim doesn't ship those subclasses; instead
# Moose::Util::throw_exception just calls Carp::croak. Some user code
# does `use Moose::Exception` directly, so we need at least an empty
# package.

use strict;
use warnings;

our $VERSION = '2.4000';

use overload
    q{""}    => sub { $_[0]->{message} // ref $_[0] },
    fallback => 1;

sub new {
    my ($class, %args) = @_;
    return bless { %args }, $class;
}

sub message {
    my $self = shift;
    return $self->{message} if defined $self->{message};
    return ref($self);
}

sub throw {
    my ($class, %args) = @_;
    require Carp;
    my $obj = $class->new(%args);
    Carp::croak($obj->message);
}

1;
__END__
=head1 NAME
Moose::Exception - PerlOnJava skeleton stub.
=cut
