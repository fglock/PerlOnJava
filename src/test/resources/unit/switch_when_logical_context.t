use strict;
use warnings;
use feature 'switch';
use Test::More;

my $matched = 0;
given ('foo') {
    when ((1 == 1) && 'bar') { $matched = 1 }
}
ok !$matched, 'constant logical when expression remains a smartmatch';

$matched = 0;
given ('foo') {
    when ((1 == 1) && $_ eq 'foo') { $matched = 1 }
}
ok $matched, 'topic-dependent logical when expression is boolean';

$matched = 0;
given ('foo') {
    when (defined(1) && 'bar') { $matched = 1 }
}
ok !$matched, 'defined logical when expression remains a smartmatch';

$matched = 0;
given (12) {
    when (/(\d+)/ and (1 <= $1 and $1 <= 12)) { $matched = 1 }
}
ok $matched, 'capture-dependent logical when expression is boolean';

done_testing;
