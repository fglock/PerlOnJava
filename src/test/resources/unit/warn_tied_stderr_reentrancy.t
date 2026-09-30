use strict;
use warnings;
use Test::More;

{
    package WarnTiedStderrReentrancy;
    our $writes = 0;
    sub TIEHANDLE { bless {}, shift }
    sub PRINT {
        $writes++;
        warn "nested warning from tied STDERR\n";
        return 1;
    }
}

{
    local *STDERR;
    tie *STDERR, 'WarnTiedStderrReentrancy';
    warn "outer warning\n";
    untie *STDERR;
}

is $WarnTiedStderrReentrancy::writes, 1,
    'warning from tied STDERR output does not recurse through the tied handle';

done_testing();
