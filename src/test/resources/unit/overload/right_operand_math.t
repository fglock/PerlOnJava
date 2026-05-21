use strict;
use warnings;
use Test::More;

{
    package RightOperandMath;
    use overload
        '/'     => \&op_divide,
        '%'     => \&op_modulus,
        '**'    => \&op_power,
        'atan2' => \&op_atan2,
        '""'    => \&stringify,
        fallback => 1;

    sub new {
        my ($class, $name) = @_;
        return bless { name => $name }, $class;
    }

    sub stringify {
        return $_[0]->{name};
    }

    sub format_op {
        my ($op, $self, $other, $swap) = @_;
        my $other_text = ref($other) ? $other->{name} : $other;
        return join ":", $op, $self->{name}, $other_text, ($swap ? 1 : 0);
    }

    sub op_divide  { return format_op('divide',  @_); }
    sub op_modulus { return format_op('modulus', @_); }
    sub op_power   { return format_op('power',   @_); }
    sub op_atan2   { return format_op('atan2',   @_); }
}

my $obj = RightOperandMath->new('obj');

is($obj / 10, 'divide:obj:10:0', 'division overload works with object on left');
is(10 / $obj, 'divide:obj:10:1', 'division overload works with object on right');

is($obj % 10, 'modulus:obj:10:0', 'modulus overload works with object on left');
is(10 % $obj, 'modulus:obj:10:1', 'modulus overload works with object on right');

is($obj ** 10, 'power:obj:10:0', 'power overload works with object on left');
is(10 ** $obj, 'power:obj:10:1', 'power overload works with object on right');

is(atan2($obj, 10), 'atan2:obj:10:0', 'atan2 overload works with object on left');
is(atan2(10, $obj), 'atan2:obj:10:1', 'atan2 overload works with object on right');

done_testing();
