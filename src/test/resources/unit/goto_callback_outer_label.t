use strict;
use warnings;
use Test::More;

sub invoke_callback {
    $_[0]->();
}

invoke_callback(sub { goto outer_label });
fail 'nonlocal goto must not return through the callback';

outer_label:
pass 'goto from callback reaches an outer label';

done_testing;
