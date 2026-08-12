use strict;
use warnings;
use Test::More tests => 3;

{
    package MinusOnly;
    use overload
        '-' => sub {
            my ($self, $other, $swapped) = @_;
            return bless {value => -$self->{value}}, ref($self)
                unless defined $other;
            return bless {value => $self->{value} - $other}, ref($self);
        },
        '0+' => sub { $_[0]{value} },
        fallback => 1;

    sub new { bless {value => $_[1]}, $_[0] }
}

my $value = MinusOnly->new(7);
my $negative = -$value;

isa_ok $negative, 'MinusOnly', "'-' overload supplies unary negation";
is 0 + $negative, 7, q{unary '-' autogeneration passes zero with the swapped flag};
is 0 + $value, 7, 'unary negation does not mutate the operand';
