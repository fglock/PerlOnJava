use strict;
use warnings;
use Test::More;

{
    package LocalTiedSparseArray;

    sub TIEARRAY { bless [], shift }
    sub FETCH { $_[0]->[$_[1]] }
    sub STORE { $_[0]->[$_[1]] = $_[2] }
    sub EXISTS { exists $_[0]->[$_[1]] }
    sub DELETE { delete $_[0]->[$_[1]] }
    sub FETCHSIZE { scalar @{$_[0]} }
    sub CLEAR { @{$_[0]} = () }
    sub EXTEND { }
}

tie my @array, 'LocalTiedSparseArray';
@array = qw(a b c);
{
    local $array[4] = 'x';
    ok(!defined $array[3], 'reading the intervening tied slot returns undef');
    is($array[4], 'x', 'localized tied slot is assigned');
}
is(scalar @array, 3, 'restoring a localized tied slot trims an observed intervening hole');

done_testing;
