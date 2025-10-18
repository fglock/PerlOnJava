use strict;
use warnings;
use Test::More tests => 6;
use Data::Dumper;

# Test autovivification with local() on array reference
{
    my $v;
    $v->[3];
    ## TODO
    ## is_deeply($v, [], 'Array reference not autovivified');

    {
        local $v->[3];
        is_deeply($v, [undef, undef, undef, undef], 'Array reference autovivified with local');
    }

    is_deeply($v, [], 'Array reference restored after local');
}

# Test autovivification with local() on hash reference
{
    my $v = {};
    {
        local $v->{aaa};
        is_deeply($v, { aaa => undef }, 'Hash reference autovivified with local');
    }

    is_deeply($v, {}, 'Hash reference restored after local');

    local $v->{aaa} = 123;
    {
        local $v->{aaa};
        is_deeply($v, { aaa => undef }, 'Hash reference autovivified with local after assignment');
    }
    is_deeply($v, { aaa => 123 }, 'Hash reference autovivified with local after assignment');
}
