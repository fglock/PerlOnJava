use strict;
use warnings FATAL => 'all';
use Test::More;

require DynaLoader;

ok(DynaLoader->can('dl_load_flags'), 'DynaLoader exposes dl_load_flags');
is(DynaLoader->dl_load_flags, 0, 'default dynamic loader flags are zero');

done_testing;
