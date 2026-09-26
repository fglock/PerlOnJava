use strict;
use warnings;
use feature qw(state refaliasing);
no warnings 'experimental::refaliasing';
use Test::More;

my $first = sub { 41 };
my $second = sub { 42 };
{
    my sub target;
    \&target = $first;
    is target(), 41, 'scalar CODE alias updates the lexical sub binding';
    is \&target, $first, 'reference reads the installed CODE';
    (\&target) = ($second);
    is target(), 42, 'list CODE alias updates the same binding';
    my @seen;
    for \&target ($first, $second) { push @seen, target() }
    is_deeply \@seen, [41, 42], 'foreach CODE aliases are visible to calls';
    is target(), 42, 'foreach restores the preceding CODE binding';
}
{
    state sub target;
    \&target = $first;
    is target(), 41, 'scalar CODE alias updates a state sub binding';
    (\&target) = ($second);
    is target(), 42, 'list CODE alias updates a state sub binding';
}
done_testing;
