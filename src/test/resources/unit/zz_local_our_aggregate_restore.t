use strict;
use warnings;
use Test::More tests => 4;

our %hash = (original => 1);
our @array = ('original');

sub mutate_local_aggregates {
    local %hash = %hash;
    local @array = @array;

    $hash{local} = 2;
    $array[0] = 'changed';
    $array[1] = 'local';
}

mutate_local_aggregates();

is_deeply(\%hash, { original => 1 },
    'local our hash restores its original contents after element assignment');
is_deeply(\@array, ['original'],
    'local our array restores its original contents after element assignment');
ok(!exists $hash{local}, 'localized hash key does not leak');
ok(!exists $array[1], 'localized array element does not leak');
