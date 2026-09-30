use strict;
use Test::More;

for my $source ('0xp3', '5p3') {
    my $warning = '';
    {
        local $SIG{__WARN__} = sub { $warning .= shift };
        eval $source;
    }
    like($warning, qr/Missing operator before "?p3"?/, "$source warns about the missing operator");
}

done_testing;
