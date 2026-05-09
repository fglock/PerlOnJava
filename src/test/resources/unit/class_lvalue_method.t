use strict;
use warnings;
use Test::More;

use experimental 'class';
no warnings 'experimental::class';

class ClassLvalueMethodTest {
    field $value :param = undef;

    method value :lvalue {
        $value
    }
}

my $obj = ClassLvalueMethodTest->new(value => 10);
is($obj->value, 10, 'class lvalue method reads field');

$obj->value = 25;
is($obj->value, 25, 'class lvalue method can be assigned through');

eval q{
    use experimental 'class';
    class main::QualifiedLvalueMethodTest {
        field $value :param = undef;

        method value :lvalue {
            $value
        }
    }
    1
} or die $@;

my $class = 'main::QualifiedLvalueMethodTest';
my $qualified = $class->new(value => 30);
$qualified->value = 35;
is($qualified->value, 35, 'main:: qualified class name dispatches to generated lvalue method');

done_testing();
