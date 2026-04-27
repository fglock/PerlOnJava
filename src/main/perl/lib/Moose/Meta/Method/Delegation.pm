package Moose::Meta::Method::Delegation;
use strict; use warnings;
our $VERSION = '2.4000';
require Moose::Meta::Method;
our @ISA = ('Moose::Meta::Method');
sub new { my ($class, %args) = @_; bless { %args }, $class }
sub delegate_to_method { $_[0]->{delegate_to_method} }
1;
