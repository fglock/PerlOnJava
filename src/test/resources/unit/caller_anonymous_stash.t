use strict;
use warnings;
use Test::More tests => 1;

package CallerAnonymousStash;
BEGIN { undef %CallerAnonymousStash:: }
sub reported_name { (caller 0)[3] }
::is(reported_name(), '__ANON__::reported_name',
    'caller names a sub in an undefined stash as anonymous');
