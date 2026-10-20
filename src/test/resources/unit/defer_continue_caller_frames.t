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
    my $trace = '';
    for my $i (1, 2) {
        defer { $trace .= "d$i" }
        next if $i == 1;
        $trace .= "b$i";
    }
    continue { $trace .= "c$i" }
    is($trace, 'd1c1b2d2c2', 'next exits the body before its continue block');
}

{
    my $trace = '';
    for my $i (1, 2) {
        defer { $trace .= "d$i" }
        $trace .= "b$i";
    }
    continue { $trace .= "c$i" }
    is($trace, 'b1d1c1b2d2c2',
        'a trailing body statement keeps defer cleanup before continue');
}

{
    my $trace = '';
    for my $i (1, 2) {
        {
            defer { $trace .= "d$i" }
        }
    }
    continue { $trace .= "c$i" }
    is($trace, 'd1c1d2c2', 'nested defer scope exits before continue');
}

{
    my $trace = '';
    for my $i (1, 2) {
        defer { $trace .= "d$i" }
        last if $i == 1;
    }
    continue { $trace .= "c$i" }
    is($trace, 'd1', 'last runs defer and skips continue');
}

{
    my $trace = '';
    {
        defer { $trace .= 'a' }
        {
            defer { $trace .= 'b' }
        }
    }
    is($trace, 'ba', 'nested defer blocks retain LIFO scope ordering');
}

{
    my $ran = 0;
    eval {
        defer { $ran++ }
        die "defer unwind\n";
    };
    is($ran, 1, 'defer runs while unwinding an exception');
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
