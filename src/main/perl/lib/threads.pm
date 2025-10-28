package threads;

# placeholder

# tid() is needed by t/loc_tools.pl
sub tid {
    return 0;
}

# create() is needed by watchdog in test.pl
sub create {
    my $class = shift;
    my $code = shift;
    # Return a simple object that can be killed
    return bless { code => $code }, $class;
}

# kill() is needed by watchdog in test.pl
sub kill {
    my $self = shift;
    # Do nothing - just a stub
    return;
}

# exit() is needed by watchdog in test.pl
sub exit {
    # Do nothing - just a stub
    return;
}

1;

