use strict;
use warnings;

use Scalar::Util qw(dualvar isdual);
use Test::More;

ok isdual(!0), 'true boolean is dual-valued';
ok isdual(!1), 'false boolean is dual-valued';
ok isdual(dualvar(0, '0')), 'explicit dualvar remains dual-valued';
ok !isdual('plain string'), 'ordinary string is not dual-valued';

done_testing;
