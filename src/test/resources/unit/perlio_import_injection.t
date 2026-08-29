use strict;
use warnings;
use Test::More;

$main::PerlIO_code_injection = 0;
{
    local $SIG{__WARN__} = sub { };
    my $ok = eval {
        PerlIO->import('via; $main::PerlIO_code_injection = 1');
        1;
    };
    ok($ok, 'PerlIO import accepts an invalid layer name safely');
}
is($main::PerlIO_code_injection, 0, 'PerlIO import does not execute its argument');

done_testing;
