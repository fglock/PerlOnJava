use strict;
use warnings;
use Test::More;

my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= shift };
    my $line = <poj_unopened_handle>;
    ok !defined $line, 'an unopened bareword diamond handle returns undef';
}

like $warning, qr/^readline\(\) on unopened filehandle poj_unopened_handle at /,
    'an unopened bareword diamond handle reports its handle name';

done_testing;
