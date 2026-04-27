package Class::MOP::Method::Accessor;

# PerlOnJava skeleton stub. Returns a Class::MOP::Method-shaped object
# representing an accessor (reader/writer/predicate/clearer).

use strict;
use warnings;
our $VERSION = '2.4000';

require Class::MOP::Method;
our @ISA = ('Class::MOP::Method');

sub new {
    my ($class, %args) = @_;
    return bless { %args }, $class;
}

sub accessor_type     { $_[0]->{accessor_type} }
sub is_inline         { 0 }
sub associated_attribute { $_[0]->{attribute} }

1;
__END__
=head1 NAME
Class::MOP::Method::Accessor - PerlOnJava skeleton stub.
=cut
