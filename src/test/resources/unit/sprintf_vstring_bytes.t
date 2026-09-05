use strict;
use warnings;
use Test::More;

use bytes;

is sprintf('%vd', v1.22.333.4444), '1.22.197.141.225.133.156',
    'bytes vector formatting consumes raw v-string UTF-8 bytes';
is sprintf('%vX', v1.22.333.4444), '1.16.C5.8D.E1.85.9C',
    'bytes vector formatting supports hexadecimal conversions';
is sprintf('%*vb', '##', v1.22.333.4444),
    '1##10110##11000101##10001101##11100001##10000101##10011100',
    'bytes vector formatting supports a custom separator';

done_testing;
