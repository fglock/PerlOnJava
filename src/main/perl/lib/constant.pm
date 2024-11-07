package constant;

use strict;
use Symbol 'qualify_to_ref';

sub import {
    my $class = shift;
    my $caller = caller;

    if (@_ == 1 && ref $_[0] eq 'HASH') {
        my $constants = shift;
        while (my ($name, $value) = each %$constants) {
            _define_constant($caller, $name, $value);
        }
    } else {
        while (@_) {
            my $name = shift;
            my $value = shift;
            _define_constant($caller, $name, $value);
        }
    }
}

sub _define_constant {
    my ($package, $name, $value) = @_;
    my $full_name = "${package}::$name";
    my $ref = qualify_to_ref($full_name);
    *$ref = sub () { $value };
}

1;

