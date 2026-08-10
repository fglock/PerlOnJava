use strict;
use warnings;
use Test::More tests => 2;

my $value = 3;

sub wrapped_lvalue :lvalue {
    $value;
}

sub lvalue_wrapper :lvalue {
    my $ret;
    if (wantarray) {
        $ret = [wrapped_lvalue()];
    }
    elsif (defined wantarray) {
        $ret = \(wrapped_lvalue());
    }
    else {
        wrapped_lvalue();
    }

    wantarray ? @$ret : $ret ? $$ret : ();
}

lvalue_wrapper() = 4;
is(lvalue_wrapper(), 4,
    'an lvalue wrapper returns its scalar target after context dispatch');
is($value, 4,
    'assignment through an lvalue wrapper updates the underlying scalar');
