use strict;
use warnings;
use Test::More tests => 4;

$_ = *pvlv_tty;
delete $::{pvlv_tty};
*$_ = *STDOUT{IO};
ok defined -t $_, 'file test uses a PVLV glob IO slot without stringifying it';

my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= shift };
    $_ = *pvlv_unopened;
    close $_;
}
like $warning, qr/close\(\) on unopened filehandle pvlv_unopened/,
    'close warning retains the PVLV glob name';

{
    use utf8;
    $_ = *pvlv_tty_ò;
    delete $::{pvlv_tty_ò};
    *$_ = *STDOUT{IO};
    ok defined -t $_, 'Unicode PVLV file test preserves its IO slot';

    $warning = '';
    local $SIG{__WARN__} = sub { $warning .= shift };
    $_ = *pvlv_unopened_ò;
    close $_;
    like $warning, qr/close\(\) on unopened filehandle pvlv_unopened_ò/,
        'close warning retains a Unicode PVLV glob name';
}
