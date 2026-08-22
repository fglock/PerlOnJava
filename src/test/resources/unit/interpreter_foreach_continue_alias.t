use strict;
use warnings;
use Test::More;

my @lexical = (1, 2, 3);
for my $value (@lexical) {
    # The alias must remain active after body fallthrough.
}
continue {
    $value *= 10;
}
is_deeply(
    \@lexical,
    [10, 20, 30],
    'continue assignment through a lexical loop variable mutates the source array',
);

our $global_value;
my @global_body = (4, 5);
for $global_value (@global_body) {
    $global_value += 10;
}
is_deeply(
    \@global_body,
    [14, 15],
    'body assignment through a global loop variable mutates the source array',
);

my @global = (4, 5);
for $global_value (@global) {
    # Global loop variables use the same continue boundary.
}
continue {
    $global_value += 10;
}
is_deeply(
    \@global,
    [14, 15],
    'continue assignment through a global loop variable mutates the source array',
);

my @global_expanded = (6, 7);
for $global_value (@global_expanded) {
    $global_value = $global_value + 10;
}
is_deeply(
    \@global_expanded,
    [16, 17],
    'expanded assignment through a global loop variable mutates the source array',
);

my @implicit = qw(alpha beta);
for (@implicit) {
    # The implicit localized $_ remains aliased in continue too.
}
continue {
    $_ = uc $_;
}
is_deeply(
    \@implicit,
    [qw(ALPHA BETA)],
    'continue assignment through implicit $_ mutates the source array',
);

done_testing;
