use strict;
use warnings;
use Test::More tests => 10;

my $integer = 4;
$integer++;
is($integer, 5, 'void postfix increment mutates an integer');

my $string = 'az';
$string++;
is($string, 'ba', 'void postfix increment retains string increment semantics');

my $undef;
$undef++;
is($undef, 1, 'void postfix increment coerces undef to one');

my $decrement = 4;
$decrement--;
is($decrement, 3, 'void postfix decrement mutates an integer');

{
    package VoidPostfixTie;
    sub TIESCALAR { bless { value => $_[1], fetches => 0, stores => 0 }, $_[0] }
    sub FETCH { $_[0]{fetches}++; return $_[0]{value} }
    sub STORE { $_[0]{stores}++; $_[0]{value} = $_[1] }
}

my $tied = tie my $tied_value, 'VoidPostfixTie', 7;
$tied_value++;
is($tied->{value}, 8, 'void postfix increment stores through a tied scalar');
is($tied->{fetches}, 1, 'void postfix increment fetches a tied scalar once');
is($tied->{stores}, 1, 'void postfix increment stores a tied scalar once');

{
    package VoidPostfixOverload;
    use overload '++' => sub { $_[0]{count}++; return $_[0] }, fallback => 1;
}

my $object = bless { count => 0 }, 'VoidPostfixOverload';
$object++;
is($object->{count}, 1, 'void postfix increment dispatches overload');

is(ref($object), 'VoidPostfixOverload', 'void postfix increment preserves overloaded object identity');

my $postfix_value = 9;
my $old = $postfix_value++;
is($old, 9, 'value context retains the original postfix result');
