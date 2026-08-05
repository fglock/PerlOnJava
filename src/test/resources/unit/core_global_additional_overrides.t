use strict;
use warnings;
use Test::More tests => 4;

our ($getgrgid_called, $unlink_called);
BEGIN {
    *CORE::GLOBAL::getgrgid = sub ($) {
        $getgrgid_called++;
        return wantarray ? qw(group passwd 123 1 2) : 'group';
    };
    *CORE::GLOBAL::unlink = sub (@) {
        $unlink_called++;
        return 7;
    };
}

is(scalar getgrgid(42), 'group', 'CORE::GLOBAL::getgrgid overrides the builtin');
is($getgrgid_called, 1, 'getgrgid override was invoked');
is(unlink('not-used'), 7, 'CORE::GLOBAL::unlink overrides the builtin');
is($unlink_called, 1, 'unlink override was invoked');
