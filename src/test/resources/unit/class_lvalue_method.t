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

eval q{
    use experimental 'class';
    class main::ParamNameLvalueMethodTest {
        field $bla_x2Efoo :param(bla.foo) = undef;

        method value :lvalue {
            $bla_x2Efoo
        }
    }
    1
} or die $@;

my $dot = main::ParamNameLvalueMethodTest->new('bla.foo' => 40);
is($dot->value, 40, 'class field :param supports dotted constructor parameter names');
$dot->value = 45;
is($dot->value, 45, 'dotted parameter field remains assignable through lvalue method');

eval q{
    use experimental 'class';
    class main::NonLvalueMethodTest {
        field $value :param = undef;

        method value {
            $value
        }
    }
    1
} or die $@;

my $readonly = main::NonLvalueMethodTest->new(value => 50);
eval { $readonly->value = 55 };
like($@, qr/Can't modify non-lvalue subroutine call/, 'class method without :lvalue cannot be assigned through');
is($readonly->value, 50, 'failed non-lvalue assignment leaves field unchanged');

eval q{
    use experimental 'class';
    class main::GeneratedAccessorMethodTest {
        use Carp;
        field $value :param(value) = undef;

        { no strict 'refs'; *{"main::GeneratedAccessorMethodTest::value"} = method :lvalue { @_ and croak "args"; $value } }
    }
    1
} or die $@;

my $generated = main::GeneratedAccessorMethodTest->new(value => 60);
is($generated->value, 60, 'anonymous method assigned to typeglob can read class field');
$generated->value = 65;
is($generated->value, 65, 'anonymous method assigned to typeglob keeps :lvalue');

done_testing();
