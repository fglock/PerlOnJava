use strict;
use warnings;
use Test::More tests => 1;

my $seen = 0;
my ($outer, $nested, $later) = (1, 1, 1);

if ($outer) {
    if ($nested) {
        goto TARGET;
    }
} elsif ($later) {
    TARGET: $seen++;
}

is($seen, 1, 'goto from nested if arm reaches label in the enclosing elsif chain');
