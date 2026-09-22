use strict;
use warnings;
use Test::More;

our $readonly_lvalue_return = $];
Internals::SvREADONLY($readonly_lvalue_return, 1);

sub nested_readonly_lvalue :lvalue { return $readonly_lvalue_return }
sub explicit_nested_readonly_lvalue :lvalue { return nested_readonly_lvalue }

is(explicit_nested_readonly_lvalue(), $],
    'explicit return keeps a nested lvalue call in rvalue context');

done_testing;
