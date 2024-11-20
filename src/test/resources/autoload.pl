use strict;
use warnings;
use feature 'say';

package X;

# Counter for test cases
my $test_count = 0;

# Attempt to call a non-existent subroutine 'callme'
sub x {
    callme(@_);
}

# Attempt to call other non-existent subroutines
sub a {
    another_missing_sub(@_);
}

sub b {
    yet_another_missing_sub(@_);
}

# Define AUTOLOAD to handle undefined subroutine calls
sub AUTOLOAD {
    our $AUTOLOAD;
    $test_count++;
    if ($AUTOLOAD eq 'X::callme' && @_ == 1 && $_[0] == 123) {
        say "ok $test_count - autoloading: $AUTOLOAD <@_>";
    } elsif ($AUTOLOAD eq 'X::another_missing_sub' && @_ == 1 && $_[0] == 456) {
        say "ok $test_count - autoloading: $AUTOLOAD <@_>";
    } elsif ($AUTOLOAD eq 'X::yet_another_missing_sub' && @_ == 1 && $_[0] == 789) {
        say "ok $test_count - autoloading: $AUTOLOAD <@_>";
    } else {
        say "not ok $test_count - unexpected AUTOLOAD call: $AUTOLOAD <@_>";
    }
}

# Call the subroutines
x(123);
a(456);
b(789);

1;

