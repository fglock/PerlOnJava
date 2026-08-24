package RegexImplementationCnameMode;

use strict;
use warnings;

our @Modes;

sub translator {
    my ($name) = @_;
    push @Modes, utf8::is_utf8($name) ? 'U' : 'B';
    return $name eq 'FOO' || $name eq 'BAZ' ? 'A' : 'B';
}

sub import {
    $^H{charnames} = \&translator;
}

1;
