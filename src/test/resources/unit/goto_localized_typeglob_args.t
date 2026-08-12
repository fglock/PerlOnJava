use strict;
use warnings;
use Test::More tests => 2;

package OuterLocalizer;

sub invoke {
    local *_ = \my $localized_scalar;
    return TailCallTarget::first('preserved');
}

package TailCallTarget;

sub first {
    my $value = shift;
    unshift @_, $value;
    goto &second;
}

sub second {
    return scalar(@_), $_[0];
}

package main;

my ($count, $value) = OuterLocalizer::invoke();
is($count, 1, 'outer localized underscore glob does not replace nested @_');
is($value, 'preserved', 'goto &sub preserves nested arguments');
