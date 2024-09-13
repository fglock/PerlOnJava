package StrictCarp;
use strict;
use warnings;
use Symbol 'qualify_to_ref';

# Error reporting subroutines
sub croak {
    my $message = _format_message(@_);
    die _caller_info(1) . ": $message\n";
}

sub carp {
    my $message = _format_message(@_);
    warn _caller_info(1) . ": $message\n";
}

sub confess {
    my $message = _format_message(@_);
    die _caller_info(1) . ": $message\n" . _stack_trace();
}

sub cluck {
    my $message = _format_message(@_);
    warn _caller_info(1) . ": $message\n" . _stack_trace();
}

# Helper to format the message
sub _format_message {
    return join('', @_);
}

# Helper to get caller info for the message
sub _caller_info {
    my ($level) = @_;
    my @call_info = caller($level);
    return "$call_info[1] line $call_info[2]";
}

# Helper to generate a stack trace
sub _stack_trace {
    my $stack = "";
    my $level = 1;
    while (my @call_info = caller($level++)) {
        $stack .= " at $call_info[1] line $call_info[2]\n";
    }
    return $stack;
}

1;

