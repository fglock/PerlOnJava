package constant;

use strict;

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
    no strict 'refs';
    # Store directly in stash as a reference - this creates a proper constant
    # that RuntimeStashEntry recognizes and sets constantValue on the RuntimeCode
    ${"${package}::"}{$name} = \$value;
}

1;

