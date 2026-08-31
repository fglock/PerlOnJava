use strict;
use warnings;
use Test::More;

{
    my $watchdog_alarm;

    END { watchdog(0); }

    sub watchdog ($;$) {
        my $timeout = shift;
        if ($watchdog_alarm) {
            alarm(0);
            undef $watchdog_alarm;
        }
        return if $timeout == 0;
        $watchdog_alarm = alarm($timeout);
    }
}

watchdog(60);

# Core test preambles install a watchdog during BEGIN-time setup.  That must
# not make a later ordinary ($$$$) declaration parse as a signature.
sub four_arguments ($$$$) { scalar @_ }

is(four_arguments(1, 2, 3, 4), 4, 'ordinary prototype remains valid after watchdog setup');
is(prototype(\&four_arguments), '$$$$', 'prototype was retained');

done_testing;
