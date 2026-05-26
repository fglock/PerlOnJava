use strict;
use warnings;

our $open_glob_defined_at_begin;
BEGIN {
    $open_glob_defined_at_begin = defined *{chr(15) . 'PEN'} ? 1 : 0;
}

use Test::More tests => 2;

ok($open_glob_defined_at_begin, '${^OPEN} typeglob exists at BEGIN time');
ok(!defined ${^OPEN}, '${^OPEN} scalar starts undef');
