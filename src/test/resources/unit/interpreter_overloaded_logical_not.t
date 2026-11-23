use strict;
use warnings;
use Test::More tests => 3;

{
    package Issue2122::Logic;
    use overload '!' => sub {
        return bless { value => 42 }, ref $_[0];
    }, fallback => 1;

    sub new { bless {}, shift }
}

my $value = Issue2122::Logic->new;
my $negated = !$value;
is(ref($negated), 'Issue2122::Logic', 'logical negation dispatches to the overload');
is(ref($negated) eq 'Issue2122::Logic' ? $negated->{value} : undef, 42,
    'logical negation preserves the overloaded result');

my $raw_negated;
{
    no overloading;
    $raw_negated = !$value;
}
is($raw_negated, '', 'no overloading keeps logical negation boolean');
