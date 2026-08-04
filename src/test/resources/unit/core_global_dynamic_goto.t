use strict;
use warnings;
use Test::More tests => 4;

our $unlink_target = sub { return 11 };

BEGIN {
    no warnings 'redefine';
    *CORE::GLOBAL::unlink = sub { goto $unlink_target };
}

is(unlink('unused'), 11, 'CORE::GLOBAL override can tail-call a global coderef');

{
    local $unlink_target = sub { $! = 5; return };
    ok(!unlink('unused'), 'CORE::GLOBAL override sees localized coderef target');
    is(0 + $!, 5, 'localized tail-call target updates errno');
}

is(unlink('unused'), 11, 'global coderef target is restored after localization');
