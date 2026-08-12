use strict;
use warnings;
use Test::More tests => 2;

sub tail_target {
    return $_[0];
}

my @replacement = ('replacement array');

is(
    sub {
        local *_ = ['localized array'];
        goto &tail_target;
    }->('original array'),
    'localized array',
    'goto uses underscore ARRAY localized by the current call frame',
);

is(
    sub {
        local *_ = \@replacement;
        goto &tail_target;
    }->('original array'),
    'replacement array',
    'goto uses aliased underscore ARRAY localized by the current call frame',
);
