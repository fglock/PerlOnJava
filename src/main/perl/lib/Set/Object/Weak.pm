package Set::Object::Weak;

use strict;
use warnings;
use base qw(Set::Object Exporter);
use Set::Object qw(blessed);

our @EXPORT_OK = qw(weak_set set);

sub new {
    my $class = shift;
    my $self = $class->SUPER::new();
    $self->weaken;
    $self->insert(@_);
    return $self;
}

sub weak_set {
    return __PACKAGE__->new(@_);
}

sub set {
    my $class = __PACKAGE__;
    if (blessed($_[0]) && $_[0]->isa('Set::Object')) {
        $class = shift->strong_pkg;
    }
    return $class->new(@_);
}

1;
