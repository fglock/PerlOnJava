use strict;
use warnings;
use Test::More tests => 8;

our $value = 'outer';
my @seen;

OUTER: for my $iteration (2, 1) {
    {
        local $value = "$value-next";
        push @seen, $value;
        next OUTER unless $iteration == 1;
    }
    push @seen, "after:$value";
}

is_deeply(
    \@seen,
    [ 'outer-next', 'outer-next', 'after:outer' ],
    'next unwinds a nested local before the next iteration',
);
is($value, 'outer', 'next leaves the package variable restored');

my $redo_count = 0;
my @redo_seen;
REDO_LOOP: while (1) {
    {
        local $value = "$value-redo";
        push @redo_seen, $value;
        redo REDO_LOOP if $redo_count++ == 0;
    }
    last REDO_LOOP;
}

is_deeply(
    \@redo_seen,
    [ 'outer-redo', 'outer-redo' ],
    'redo unwinds a nested local before restarting the body',
);
is($value, 'outer', 'redo leaves the package variable restored');

LAST_LOOP: while (1) {
    {
        local $value = 'inner-last';
        last LAST_LOOP;
    }
}

is($value, 'outer', 'last unwinds a nested local while exiting the loop');

my $postfix_count = 0;
POSTFIX_LOOP: while ($postfix_count++ < 2) {
    {
        local $value = "$value-postfix";
        next POSTFIX_LOOP unless $postfix_count == 2;
    }
}

is($value, 'outer', 'postfix labeled next unwinds a nested local');
is($postfix_count, 3, 'postfix labeled next retains normal loop progress');
is(scalar(@redo_seen), 2, 'redo executes exactly two iterations');
