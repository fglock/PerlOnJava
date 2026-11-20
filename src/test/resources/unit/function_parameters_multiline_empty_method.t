use strict;
use warnings;
use Test::More tests => 2;
use Function::Parameters;

method simple_method() { $self }

method empty_method
 (
 
 )
 : #hello
 prototype(
  $
 )
 {@_ == 0 or return;
     $self
 }

is(simple_method(42), 42, 'ordinary empty method signature parses');
is(empty_method(42), 42, 'multiline empty method signature inserts its invocant');
