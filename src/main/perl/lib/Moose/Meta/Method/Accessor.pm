package Moose::Meta::Method::Accessor;
use strict; use warnings;
our $VERSION = '2.4000';
require Class::MOP::Method::Accessor;
our @ISA = ('Class::MOP::Method::Accessor');
sub new { my ($class, %args) = @_; bless { %args }, $class }
1;
