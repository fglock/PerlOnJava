package Class::MOP::Package;

# PerlOnJava skeleton stub. Mostly a no-op base class for Class::MOP::Class.

use strict;
use warnings;
our $VERSION = '2.4000';

sub new {
    my ($class, %args) = @_;
    $args{name} //= $args{package};
    return bless { %args }, $class;
}

sub name              { $_[0]->{name} }
sub package_name      { $_[0]->{name} }
sub namespace         {
    my $self = shift;
    no strict 'refs';
    return \%{ "$self->{name}::" };
}
sub get_package_symbol {
    my ($self, $name) = @_;
    no strict 'refs';
    return *{ "$self->{name}::$name" };
}
sub list_all_package_symbols {
    my ($self, $type) = @_;
    no strict 'refs';
    my $stash = \%{ "$self->{name}::" };
    return grep { !/::\z/ } keys %$stash;
}

1;
__END__
=head1 NAME
Class::MOP::Package - PerlOnJava skeleton stub.
=cut
