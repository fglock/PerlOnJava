use strict;
use warnings;
use Test::More tests => 2;

{
    package OneShotFilter;
    use Filter::Util::Call;
    sub import {
        filter_add(sub {
            $_ = '$main::one_shot_injected = 41;';
            filter_del();
            return 1;
        });
    }
}
BEGIN { $INC{'OneShotFilter.pm'} = __FILE__ }

use OneShotFilter;
$main::one_shot_following = 42;

is($main::one_shot_injected, 41, 'one-shot filter injects one chunk');
is($main::one_shot_following, 42, 'source after filter_del remains intact');
