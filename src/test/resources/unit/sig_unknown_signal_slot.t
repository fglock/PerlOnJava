use strict;
use warnings;
use Test::More;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $SIG{HUNGRY} = 'mmm_pie';
}

is $SIG{HUNGRY}, 'mmm_pie',
    'an unknown signal name retains its assigned handler';
like join('', @warnings), qr/^No such signal: SIGHUNGRY/,
    'an unknown signal name emits the signal warning';
is delete $SIG{HUNGRY}, 'mmm_pie',
    'an unknown signal entry can be deleted after its warning';

done_testing;
