package MyCarp;

use strict;
use warnings;
use Exporter 'import';

our @EXPORT_OK = qw(longmess shortmess);

sub longmess {
    my ($message) = @_;
    my $mess = _get_message($message);
    my $trace = _get_stack_trace(0);
    return $mess . $trace;
}

sub shortmess {
    my ($message) = @_;
    my $mess = _get_message($message);
    my $trace = _get_stack_trace(1);
    return $mess . $trace;
}

sub _get_message {
    my ($message) = @_;
    return defined $message ? $message : "Unnamed error";
}

sub _get_stack_trace {
    my ($is_short) = @_;
    my $trace = "";
    my $i = 1;  # Start at 1 to skip this function call

    while (my @caller = caller($i++)) {
        my ($package, $filename, $line, $subroutine) = @caller;
        $trace .= "\t$subroutine called at $filename line $line\n";
        last if $is_short;  # For shortmess, only include one level of call stack
    }

    return $trace;
}

1;

