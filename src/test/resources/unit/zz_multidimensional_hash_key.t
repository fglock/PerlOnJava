use strict;
use warnings;
no warnings 'deprecated';
use Test::More tests => 4;

my %cache = (
    "alpha\034beta" => 'default separator',
    'left:right'     => 'localized separator',
);

my ($first, $second) = qw(alpha beta);
is($cache{$first, $second}, 'default separator',
    'multi-expression hash key uses the default SUBSEP');

{
    local $; = ':';
    my ($left, $right) = qw(left right);
    is($cache{$left, $right}, 'localized separator',
        'multi-expression hash key observes localized SUBSEP');

    my $cache_ref = \%cache;
    is($cache_ref->{$left, $right}, 'localized separator',
        'hashref multi-expression key observes localized SUBSEP');
}

is($cache{'alpha', 'beta'}, 'default separator',
    'literal expressions are joined into one hash key');
