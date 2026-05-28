use strict;
use warnings;
use Test::More;

{
    package ConcatOverload;
    use overload
        '.'  => 'concat',
        '""' => 'as_string',
        fallback => 1;

    sub new {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }

    sub as_string {
        return $_[0]->{value};
    }

    sub concat {
        my ($self, $other, $reversed) = @_;
        my $left  = $reversed ? $other         : $self->{value};
        my $right = $reversed ? $self->{value} : $other;
        return ref($self)->new("($left.$right)");
    }
}

my $obj = ConcatOverload->new('X');

my $left = $obj . 'R';
isa_ok($left, 'ConcatOverload', 'left operand concat returns overloaded object');
is("$left", '(X.R)', 'left operand concat calls overload method');

my $right = 'L' . $obj;
isa_ok($right, 'ConcatOverload', 'right operand concat returns overloaded object');
is("$right", '(L.X)', 'right operand concat passes reversed flag');

my $chain = 'A' . ConcatOverload->new('B') . 'C';
isa_ok($chain, 'ConcatOverload', 'chained concat preserves overloaded object');
is("$chain", '((A.B).C)', 'chained concat dispatches overload at each step');

{
    package MessageLikeConcat;
    use overload
        '.'  => 'concat',
        '""' => 'as_string',
        fallback => 1;

    sub new {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }

    sub as_string {
        return $_[0]->{value};
    }

    sub concat {
        my ($self, $other, $reversed) = @_;
        my $left  = $reversed ? "$other"       : $self->{value};
        my $right = $reversed ? $self->{value} : "$other";
        return ref($self)->new($left . $right);
    }
}

my $hello = MessageLikeConcat->new('Hello');
my $world = MessageLikeConcat->new('World!');
my $interpolated = "$hello $world\n";
isa_ok($interpolated, 'MessageLikeConcat', 'multi-segment interpolation preserves overloaded concat');
is("$interpolated", "Hello World!\n", 'multi-segment interpolation dispatches concat overload');

done_testing();
