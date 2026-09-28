use strict;
use warnings;
use Test::More;

{
    my @array;
    $array[1] = 1;
    sub { unshift @array, 7; $_[0] = 3 }->($array[0]);
    is_deeply(\@array, [7, 3, 1],
        'an alias to a sparse array slot follows the slot after unshift');
}

{
    my @array;
    $array[1] = 1;
    map { unshift @array, 7; $_ = 3; goto after_map } @array;
  after_map:
    is_deeply(\@array, [7, 3, 1],
        'a sparse array slot used by map follows the slot after unshift');
}

{
    my @array;
    $array[3] = 1;
    eval { $array[-5] = 42 };
    like($@, qr/Modification of non-creatable array value attempted, subscript -5/,
        'a negative index beyond the beginning is retained in the diagnostic');
}

{
    my @array;
    $array[3] = 1;
    sub { $_[0] = 3 }->($array[-2]);
    is_deeply(\@array, [undef, 3, undef, 1],
        'a negative sparse slot passed through @_ aliases Perl\'s retained hole');
}

done_testing();
