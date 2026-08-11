use strict;
use warnings;
use Test::More tests => 2;

{
    package IndirectNamedArgs::Error;

    sub throw {
        my ($class, %args) = @_;
        return join q{|}, $class, $args{-text}, $args{-code};
    }
}

my $thrown = throw IndirectNamedArgs::Error -text => 'bad value', -code => 17;
is(
    $thrown,
    'IndirectNamedArgs::Error|bad value|17',
    'qualified indirect-object call accepts leading dash named arguments',
);

my $negative = -4;
is(1 + $negative, -3, 'ordinary unary minus remains arithmetic');
