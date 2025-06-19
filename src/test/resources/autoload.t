use strict;
use warnings;

package X;

use Test::More tests => 3;

sub x {
    callme(@_);
}

sub a {
    another_missing_sub(@_);
}

sub b {
    yet_another_missing_sub(@_);
}

sub AUTOLOAD {
    our $AUTOLOAD;
    if ($AUTOLOAD eq 'X::callme' && @_ == 1 && $_[0] == 123) {
        pass("autoloading: $AUTOLOAD called with correct args <@_>");
    } elsif ($AUTOLOAD eq 'X::another_missing_sub' && @_ == 1 && $_[0] == 456) {
        pass("autoloading: $AUTOLOAD called with correct args <@_>");
    } elsif ($AUTOLOAD eq 'X::yet_another_missing_sub' && @_ == 1 && $_[0] == 789) {
        pass("autoloading: $AUTOLOAD called with correct args <@_>");
    } else {
        fail("unexpected AUTOLOAD call: $AUTOLOAD <@_>");
    }
}

x(123);
a(456);
b(789);

1;
