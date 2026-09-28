use strict;
use warnings;
use Test::More tests => 3;

# BEGIN parses in a separate compilation context. PerlOnJava carries outer
# lexicals through that context as synthetic `our` aliases; arrays and hashes
# must remain closure containers rather than being re-resolved as main globals.
my (@values, %labels, $summary);
BEGIN {
    push @values, qw(alpha beta);
    $labels{kind} = 'capture';
    $summary = join ':', @values;
}

is_deeply \@values, [qw(alpha beta)], 'BEGIN writes to its captured array';
is_deeply \%labels, { kind => 'capture' }, 'BEGIN writes to its captured hash';
is $summary, 'alpha:beta', 'BEGIN reads its captured array';
