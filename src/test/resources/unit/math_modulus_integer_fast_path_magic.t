use strict;
use warnings;
use Test::More tests => 5;

{
    package Local::ModulusTie;
    sub TIESCALAR {
        my ($class, $value) = @_;
        bless { value => $value, fetches => 0 }, $class;
    }
    sub FETCH { ++$_[0]{fetches}; $_[0]{value} }
    sub STORE { $_[0]{value} = $_[1] }
}

tie my $left, 'Local::ModulusTie', 17;
tie my $right, 'Local::ModulusTie', 5;
is($left % $right, 2, 'tied integer values use their FETCH results');
is(tied($left)->{fetches}, 1, 'left tied operand is fetched exactly once');
is(tied($right)->{fetches}, 1, 'right tied operand is fetched exactly once');

{
    package Local::ModulusOverload;
    our $calls = 0;
    use overload '%' => sub { ++$calls; 23 }, fallback => 1;
}

my $overloaded = bless \(my $value = 17), 'Local::ModulusOverload';
is($overloaded % 5, 23, 'overloaded modulus bypasses the native integer fast path');
is($Local::ModulusOverload::calls, 1, 'overloaded modulus is dispatched once');
