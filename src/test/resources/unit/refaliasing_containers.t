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

done_testing;
