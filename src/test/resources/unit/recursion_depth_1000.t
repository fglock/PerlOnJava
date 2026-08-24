use strict;
use warnings;
use Test::More tests => 1;

no warnings 'recursion';

sub descend {
    my ($remaining) = @_;
    return 0 unless $remaining;
    return 1 + descend($remaining - 1);
}

is(descend(1000), 1000,
    'ordinary Perl recursion reaches an ecosystem guard depth of 1000');
