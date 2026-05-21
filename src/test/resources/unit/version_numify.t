use strict;
use warnings;
use Test::More;
use version;

is(version->parse('v1.5')->numify, '1.005000', 'dotted v-version numifies with component padding');
is(version->parse('v1.5')->normal, 'v1.5.0', 'dotted v-version normal form preserves component value');

is(version->parse('1.5')->numify, '1.500', 'decimal version numifies with decimal padding');
is(version->parse('1.5')->normal, 'v1.500.0', 'decimal version normal form groups decimal digits');

is(version->parse('1.2345')->numify, '1.234500', 'long decimal version numifies to grouped width');
is(version->parse('1.2345')->normal, 'v1.234.500', 'long decimal version normal form pads patch group');

is(version->parse('v1.2.3')->numify, '1.002003', 'three-part v-version numifies as dotted components');
is(version->parse('1.2.3')->numify, '1.002003', 'three-part dotted version without v is qv-style');

done_testing();
