use strict;
use warnings;
use Test::More;

like("\x{00DF}s", qr/^s\x{00DF}$/iu,
    'adjacent sharp-s folds can repartition three s characters');
like("s\x{00DF}", qr/^\x{00DF}s$/iu,
    'reverse adjacent sharp-s folds can repartition three s characters');
unlike("\x{00DF}x", qr/^s\x{00DF}$/iu,
    'adjacent sharp-s partition still rejects a different suffix');
unlike("x\x{00DF}", qr/^\x{00DF}s$/iu,
    'reverse adjacent sharp-s partition still rejects a different prefix');

done_testing;
