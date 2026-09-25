use v5.36;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use Test::More;

my ($scalar, @array, %hash);
my ($source_scalar, @source_array, %source_hash) = (11, (22, 23), (answer => 42));

my $select_array = 1;
\($scalar, $select_array ? @array : %hash) = (\$source_scalar, \@source_array);
is $scalar, 11, 'conditional scalar branch aliases its referent';
is \@array, \@source_array, 'conditional array branch aliases aggregate identity';
$array[0] = 24;
is $source_array[0], 24, 'conditional array alias shares element cells';

my $select_hash = 0;
\($scalar, $select_hash ? @array : %hash) = (\$source_scalar, \%source_hash);
is \%hash, \%source_hash, 'conditional hash branch aliases aggregate identity';
$hash{answer} = 43;
is $source_hash{answer}, 43, 'conditional hash alias shares storage';

done_testing;
