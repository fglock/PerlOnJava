package Proc::ProcessTable::Process;

use strict;
use warnings;
use Carp qw(croak);

our $VERSION = '0.02';
our $AUTOLOAD;

sub AUTOLOAD {
    my $self = shift;
    my $type = ref($self) or croak "$self is not an object";
    (my $name = $AUTOLOAD) =~ s/.*://;
    croak "Can't access `$name' field in class $type"
        unless exists $self->{$name};
    return @_ ? ($self->{$name} = shift) : $self->{$name};
}

sub kill {
    my ($self, $signal) = @_;
    return CORE::kill($signal, $self->{pid});
}

sub DESTROY { }

1;
