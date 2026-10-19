use strict;
use warnings;
use Test::More;

{
    my @array;
    $array[1] = 1;
    map { unshift @array, 7; $_ = 3; goto after_map } @array;
  after_map:
    is_deeply(\@array, [7, 3, 1],
        'map can leave through a surrounding label after shifting a sparse array');
}

{
    my @array;
    $#array++;
    $array[1] = 1;
    map { unshift @array, 7; $_ = 3; goto after_magical_map } @array;
  after_magical_map:
    is_deeply(\@array, [7, 3, 1],
        'map can leave through a surrounding label after shifting a magical sparse array');
}

done_testing();
