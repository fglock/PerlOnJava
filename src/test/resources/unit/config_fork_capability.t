use strict;
use warnings;

use Config;
use Test::More tests => 2;

ok(!$Config{d_fork} || $Config{d_fork} eq 'define',
    'real fork capability uses the standard Config value');

ok($Config{d_fork} || $Config{d_pseudofork},
    'a Perl without real fork advertises the non-real fork classification');
