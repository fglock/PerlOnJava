use strict;
use warnings;
use Test::More;
use feature 'class';
no warnings 'experimental::class';

class InheritedClassNameBase 1.23 {
    method runtime_class { __CLASS__ }
}
class InheritedClassNameChild 1.23 :isa(InheritedClassNameBase) { }

is(InheritedClassNameChild->new->runtime_class, 'InheritedClassNameChild',
    '__CLASS__ in an inherited method reports the runtime class');
is(InheritedClassNameChild->VERSION, '1.23',
    'a class declaration installs its declared version');

done_testing;
