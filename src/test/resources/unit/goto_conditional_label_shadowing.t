use strict;
use warnings;
use utf8;
use Test::More;

{
    no warnings 'exiting';
    eval {
        goto ここ;
        if (undef) {
            ここ: { my $unused = 'dead branch'; }
        }
    };
}

no warnings 'exiting';
{
    goto ここ;
    ここ: { pass('a later same-spelled label remains a valid target'); }
}

done_testing();
