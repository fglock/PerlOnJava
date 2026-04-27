package Moose::Meta::Method::Constructor;
use strict; use warnings;
our $VERSION = '2.4000';
require Moose::Meta::Method;
our @ISA = ('Moose::Meta::Method');
sub new { my ($class, %args) = @_; bless { %args }, $class }
1;
