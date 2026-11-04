use strict;
use warnings;
use Test::More tests => 1;

{
    package ForwardCanDeclaredStub;
    our $AUTOLOAD;

    sub can;

    sub AUTOLOAD {
        return 'loaded from AUTOLOAD' if $AUTOLOAD =~ /::can\z/;
        die "unexpected AUTOLOAD call: $AUTOLOAD";
    }
}

is(
    ForwardCanDeclaredStub->can('some_method'),
    'loaded from AUTOLOAD',
    'a declared can stub dispatches to its package AUTOLOAD',
);
