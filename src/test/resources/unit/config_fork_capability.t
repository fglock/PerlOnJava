use strict;
use warnings;

use Config;
use Test::More tests => 2;

ok(!$Config{d_fork} || $Config{d_fork} eq 'define',
    'real fork capability uses the standard Config value');

ok(!defined $Config{d_pseudofork} || $Config{d_pseudofork} eq 'define',
    'pseudo-fork capability uses the standard Config value when available');
