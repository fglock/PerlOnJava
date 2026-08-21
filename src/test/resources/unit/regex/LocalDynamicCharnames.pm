package LocalDynamicCharnames;

use strict;
use warnings;

our %seen;

sub translator {
    my ($name) = @_;
    return 'foo' if $name eq 'FOO';
    return 'bar' if $name eq 'BAR';
    return ''    if $name eq 'EMPTY';
    return chr(65 + $seen{$name}++) if $name eq 'EVIL';
    return;
}

sub import {
    $^H{charnames} = \&translator;
}

1;
