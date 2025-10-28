package threads;

# Stub implementation - threads are not actually supported in PerlOnJava
# This provides minimal API so test harness can detect lack of thread support
# and fall back to alarm() for watchdog functionality

# tid() is needed by t/loc_tools.pl
sub tid {
    return 0;
}

# create() is needed by watchdog in test.pl
# Returns immediately-detached stub object so watchdog falls through to alarm()
sub create {
    my $class = shift;
    my $code = shift;
    # Return object marked as detached since we don't actually run threads
    return bless { code => $code, detached => 1 }, $class;
}

# kill() is needed by watchdog in test.pl
sub kill {
    my $self = shift;
    return;  # No-op
}

# exit() is needed by watchdog in test.pl  
sub exit {
    return;  # No-op
}

# is_running() - return false since threads don't actually run
sub is_running {
    my $self = shift;
    return 0;  # Thread immediately "finished"
}

# is_detached() - return true since we mark threads as detached on creation
sub is_detached {
    my $self = shift;
    return $self->{detached} || 0;
}

# detach() - mark thread as detached
sub detach {
    my $self = shift;
    $self->{detached} = 1;
    return;
}

# yield() - no-op since threads don't run
sub yield {
    return;
}

1;

