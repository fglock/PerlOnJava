use strict;
use warnings;
use Test::More;

{
    package ExplicitAssignConcat;
    our @CALLS;
    use overload
        '.=' => sub {
            my ($left, $right, $reversed) = @_;
            push @CALLS, ['.=', $reversed ? 1 : 0, "$right"];
            $left->[0] .= $right;
            return $left;
        },
        '=' => sub {
            my ($old) = @_;
            push @CALLS, ['='];
            return bless [$old->[0]], __PACKAGE__;
        },
        '""' => sub { $_[0][0] };
}

my $value = bless ['L'], 'ExplicitAssignConcat';
my $alias = $value;
$value .= 'R';

is("$value", 'LR', 'explicit concat-assignment overload supplies new lhs value');
is("$alias", 'L', 'copy constructor protects an existing alias before mutator');
is_deeply(
    \@ExplicitAssignConcat::CALLS,
    [['='], ['.=', 0, 'R']],
    'copy constructor runs before the explicit concat-assignment overload',
);
is(ref($value), 'ExplicitAssignConcat', 'explicit concat-assignment result remains an object');

done_testing;
