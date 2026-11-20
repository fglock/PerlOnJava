use strict;
use warnings;
use Test::More tests => 2;
use Function::Parameters { clathod => 'classmethod' };

clathod simple_classmethod() { $class }

clathod empty_classmethod
 (
 
 )
 : #hello
 prototype(
  $
 )
 {@_ == 0 or return;
     $class
 }

is(simple_classmethod(42), 42, 'ordinary empty classmethod signature parses');
is(empty_classmethod(42), 42, 'multiline empty classmethod signature inserts its invocant');
