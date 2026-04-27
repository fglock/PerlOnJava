package Moose::Meta::Method;

# PerlOnJava skeleton stub. Inherits from Class::MOP::Method.

use strict;
use warnings;
our $VERSION = '2.4000';

require Class::MOP::Method;
our @ISA = ('Class::MOP::Method');

sub wrap          { my ($class, %args) = @_; return bless { %args }, $class }
sub new           { my ($class, %args) = @_; return bless { %args }, $class }

1;
__END__
=head1 NAME
Moose::Meta::Method - PerlOnJava skeleton stub.
=cut
