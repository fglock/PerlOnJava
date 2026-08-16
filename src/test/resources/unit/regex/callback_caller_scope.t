use strict;
use warnings;
use feature 'current_sub';

print "1..4\n";

sub enclosing {
    'a' =~ /(?{
        my $name = (caller(0))[3];
        print defined($name) && $name eq 'main::enclosing'
            ? "ok 1 - callback caller is its enclosing sub\n"
            : "not ok 1 - callback caller is its enclosing sub\n";
    })a/;
}
enclosing();

sub called_from_callback {
    my $self = (caller(0))[3];
    my $outer = (caller(1))[3];
    print defined($self) && $self eq 'main::called_from_callback'
        ? "ok 2 - real sub frame inside callback is preserved\n"
        : "not ok 2 - real sub frame inside callback is preserved\n";
    print !defined($outer)
        ? "ok 3 - callback frame is hidden from nested sub\n"
        : "not ok 3 - callback frame is hidden from nested sub\n";
}
'a' =~ /(?{ called_from_callback() })a/;

sub callback_self {
    my $enclosing = __SUB__;
    'a' =~ /(?{
        print __SUB__ == $enclosing
            ? "ok 4 - callback inherits enclosing __SUB__\n"
            : "not ok 4 - callback inherits enclosing __SUB__\n";
    })a/;
}
callback_self();
