use strict;
use warnings;
use Test::More;
use feature 'class';
no warnings 'experimental::class';

my $outside_method = eval q{
    class FieldScopeOutsideMethod {
        field $value;
        $value = 1;
    }
    1;
};
ok(!$outside_method, 'a field cannot be used in class-body code');
like($@, qr/Field \$value is not accessible outside a method/,
    'class-body field use has the Perl diagnostic');

my $regular_sub = eval q{
    class FieldScopeRegularSub {
        field $value;
        sub use_value { $value }
    }
    1;
};
ok(!$regular_sub, 'a field cannot be used in a regular subroutine');
like($@, qr/Field \$value is not accessible outside a method/,
    'regular-sub field use has the Perl diagnostic');

my $nested_class = eval q{
    class FieldScopeOuter {
        field $value;
        class FieldScopeInner {
            method use_value { $value }
        }
    }
    1;
};
ok(!$nested_class, 'a nested class does not inherit its enclosing class fields');
like($@, qr/Field \$value of "FieldScopeOuter" is not accessible in a method of "FieldScopeInner"/,
    'nested-class field use identifies both classes');

done_testing;
