use strict;
use warnings;
use Test::More;

no warnings 'experimental::refaliasing';
use feature 'refaliasing';

my @target_array;
my @source_array = qw(alpha beta);
\@target_array = \@source_array;
push @source_array, 'gamma';
is_deeply(\@target_array, [qw(alpha beta gamma)], 'array refaliasing shares the source array');

my %target_hash;
my %source_hash = (alpha => 1);
\%target_hash = \%source_hash;
$source_hash{beta} = 2;
is($target_hash{beta}, 2, 'hash refaliasing shares the source hash');

my @hashrefs = (
    { name => 'first', count => 1 },
    { name => 'second', count => 2 },
);
my @seen;
for \%_ (@hashrefs) {
    push @seen, $_{name};
    $_{count}++;
}
is_deeply(\@seen, [qw(first second)],
    'foreach hash reference aliases the global loop hash on every iteration');
is_deeply(\@hashrefs, [
    { name => 'first', count => 2 },
    { name => 'second', count => 3 },
], 'writes through a foreach hash reference alias mutate each source hash');

our @loop_array;
my @arrayrefs = ([1], [2]);
my @array_seen;
for \@loop_array (@arrayrefs) {
    push @array_seen, $loop_array[0];
    $loop_array[0]++;
}
is_deeply(\@array_seen, [1, 2],
    'foreach array reference aliases rebind on every iteration');
is_deeply(\@arrayrefs, [[2], [3]],
    'writes through a foreach array reference alias mutate each source array');

done_testing;
