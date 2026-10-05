use strict;
use warnings;
use utf8;
use Test::More;

my $name = 'Sʎｍ000';
{
    no strict 'refs';
    ok(!defined *{$name}, 'first symbolic glob lookup does not vivify');
    ok(!defined ${$name}, 'symbolic scalar lookup does not vivify');
    ok(!defined *{$name}, 'scalar lookup leaves the glob absent');
    ok(!defined &{$name}, 'symbolic code lookup does not vivify');
    ok(!defined *{$name}, 'code lookup leaves the glob absent');

    sub оઓnḲ () { 'Value' }
    delete $::{оઓnḲ};
    $::{оઓnḲ} = \ 'Value';

    sub non_dangling {
        my $warning = '';
        local $SIG{__WARN__} = sub { $warning = $_[0] };
        *{'z앞'} = \&{'оઓnḲ'};
        is($warning, '', 'exporting the scalar constant does not warn');
    }

    non_dangling();
    is(ref $::{оઓnḲ}, 'SCALAR', 'export preserves the original scalar constant');
    is(eval 'z앞', 'Value', 'exported constant keeps its value');
    is(ref $::{z앞}, 'SCALAR', 'exported target remains a scalar constant');
}

done_testing();
