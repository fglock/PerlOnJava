use strict;
use warnings;
use Test::More;
use feature 'defer';
no warnings 'experimental::defer';

{
    my $x = '';
    foreach my $i (1, 2) {
        defer { $x .= "d$i" }
    }
    continue {
        $x .= "c$i";
    }
    is($x, 'c1d1c2d2', 'continue runs before deferred cleanup for each iteration');
}

{
    my $callerstr;
    sub capture_caller_around_defer {
        defer { $callerstr = join ':', (caller(0))[0, 1, 2], (caller(1))[0, 1, 2] }
    }

    my $line = __LINE__ + 1;
    capture_caller_around_defer();
    is($callerstr, "main:$0:$line",
        'caller omits the implementation frame for a deferred block');
}

done_testing;
