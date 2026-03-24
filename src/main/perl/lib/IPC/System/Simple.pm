package IPC::System::Simple;

# PerlOnJava native implementation of IPC::System::Simple
#
# This is a simplified implementation that provides core functionality
# without the Windows-specific code that causes issues in PerlOnJava.
# The original module uses constants in dead code branches that PerlOnJava
# doesn't optimize away, causing "Bareword not allowed" errors.

use strict;
use warnings;
use Carp;
use Config;
use List::Util qw(first);
use Scalar::Util qw(tainted);

our $VERSION = '1.30';
our @ISA = qw(Exporter);
our @EXPORT_OK = qw(
    capture capturex
    run runx
    system systemx
    $EXITVAL EXIT_ANY
);

our $EXITVAL = -1;

use constant EXIT_ANY_CONST => -1;
use constant EXIT_ANY       => [ EXIT_ANY_CONST ];

# Platform detection constants (for compatibility with tests)
use constant WINDOWS => ($^O eq 'MSWin32');
use constant VMS     => ($^O eq 'VMS');

# Error message templates
use constant FAIL_START     => q{"%s" failed to start: "%s"};
use constant FAIL_SIGNAL    => q{"%s" died to signal "%s" (%d)%s};
use constant FAIL_BADEXIT   => q{"%s" unexpectedly returned exit value %d};
use constant FAIL_UNDEF     => q{%s called with undefined command};
use constant FAIL_TAINT     => q{%s called with tainted argument "%s"};
use constant FAIL_TAINT_ENV => q{%s called with tainted environment $ENV{%s}};

# Signal name lookup
my @Signal_from_number = split(' ', $Config{sig_name});

# Environment variables to check for taint
my @Check_tainted_env = qw(PATH IFS CDPATH ENV BASH_ENV);

# system simply calls run
no warnings 'once';
*system  = \&run;
*systemx = \&runx;
use warnings;

sub run {
    _check_taint(@_);
    my ($valid_returns, $command, @args) = _process_args(@_);

    if (@args) {
        return systemx($valid_returns, $command, @args);
    }

    # Single-arg system call (uses shell)
    {
        no warnings 'exec';
        CORE::system($command);
    }

    return _process_child_error($?, $command, $valid_returns);
}

sub runx {
    _check_taint(@_);
    my ($valid_returns, $command, @args) = _process_args(@_);

    # Use multi-arg system which bypasses the shell
    # PerlOnJava's multi-arg system uses ProcessBuilder and returns -1
    # if the command doesn't exist, matching native Perl behavior
    no warnings 'exec';
    CORE::system($command, @args);

    return _process_child_error($?, $command, $valid_returns);
}

sub capture {
    _check_taint(@_);
    my ($valid_returns, $command, @args) = _process_args(@_);

    if (@args) {
        return capturex($valid_returns, $command, @args);
    }

    $EXITVAL = -1;
    my $wantarray = wantarray();

    no warnings 'exec';

    if ($wantarray) {
        my @results = qx($command);
        _process_child_error($?, $command, $valid_returns);
        return @results;
    }

    my $results = qx($command);
    _process_child_error($?, $command, $valid_returns);
    return $results;
}

sub capturex {
    _check_taint(@_);
    my ($valid_returns, $command, @args) = _process_args(@_);

    $EXITVAL = -1;
    my $wantarray = wantarray();

    # Use open with list form to bypass the shell
    # This properly returns -1 if the command doesn't exist
    my $fh;
    if (!open($fh, "-|", $command, @args)) {
        croak sprintf(FAIL_START, $command, $!);
    }

    my @results;
    my $results;

    if ($wantarray) {
        @results = <$fh>;
    } else {
        local $/;
        $results = <$fh>;
    }

    close($fh);
    _process_child_error($?, $command, $valid_returns);

    return $wantarray ? @results : $results;
}

# Quote a command and its arguments for shell execution
sub _quote_command {
    my ($cmd, @args) = @_;
    
    # Quote each argument to protect special characters
    my @quoted;
    for my $arg ($cmd, @args) {
        # Use single quotes and escape any single quotes in the argument
        my $quoted = $arg;
        $quoted =~ s/'/'\\''/g;
        push @quoted, "'$quoted'";
    }
    
    return join(' ', @quoted);
}

sub _check_taint {
    return if not ${^TAINT};
    my $caller = (caller(1))[3];
    foreach my $var (@_) {
        if (tainted($var)) {
            croak sprintf(FAIL_TAINT, $caller, $var);
        }
    }
    foreach my $var (@Check_tainted_env) {
        if (tainted($ENV{$var})) {
            croak sprintf(FAIL_TAINT_ENV, $caller, $var);
        }
    }
}

sub _process_child_error {
    my ($child_error, $command, $valid_returns) = @_;

    $EXITVAL = -1;

    if ($child_error == -1) {
        croak sprintf(FAIL_START, $command, $!);
    } elsif (($child_error & 0x7f) == 0) {
        # WIFEXITED - normal exit
        $EXITVAL = ($child_error >> 8) & 0xff;  # WEXITSTATUS
        return _check_exit($command, $EXITVAL, $valid_returns);
    } elsif (($child_error & 0x7f) > 0 && ($child_error & 0x7f) < 0x7f) {
        # WIFSIGNALED - killed by signal
        my $signal_no = $child_error & 0x7f;  # WTERMSIG
        my $signal_name = $Signal_from_number[$signal_no] || "UNKNOWN";
        my $coredump = ($child_error & 0x80) ? " and dumped core" : "";
        croak sprintf(FAIL_SIGNAL, $command, $signal_name, $signal_no, $coredump);
    }

    croak "'$command' ran without exit value or signal";
}

sub _check_exit {
    my ($command, $exitval, $valid_returns) = @_;

    # EXIT_ANY accepts any exit value
    if (@$valid_returns == 1 && $valid_returns->[0] == EXIT_ANY_CONST) {
        return $exitval;
    }

    if (not defined first { $_ == $exitval } @$valid_returns) {
        croak sprintf(FAIL_BADEXIT, $command, $exitval);
    }
    return $exitval;
}

sub _process_args {
    my $valid_returns = [0];
    my $caller = (caller(1))[3];

    if (not @_) {
        croak "$caller called with no arguments";
    }

    if (ref $_[0] eq "ARRAY") {
        $valid_returns = shift(@_);
    }

    if (not @_) {
        croak "$caller called with no command";
    }

    my $command = shift(@_);

    if (not defined $command) {
        croak sprintf(FAIL_UNDEF, $caller);
    }

    return ($valid_returns, $command, @_);
}

# Alias for POSIX compatibility
sub WIFEXITED   { (($_[0] // 0) & 0x7f) == 0 }
sub WEXITSTATUS { (($_[0] // 0) >> 8) & 0xff }
sub WIFSIGNALED { my $s = ($_[0] // 0) & 0x7f; $s > 0 && $s < 0x7f }
sub WTERMSIG    { ($_[0] // 0) & 0x7f }

1;

__END__

=head1 NAME

IPC::System::Simple - Run commands simply, with detailed diagnostics

=head1 SYNOPSIS

    use IPC::System::Simple qw(system capture run);

    # Run a command, die on failure
    run("some_command");
    
    # Capture output
    my $output = capture("some_command");
    my @lines = capture("some_command");

=head1 DESCRIPTION

This is a PerlOnJava-native implementation of IPC::System::Simple that
provides the core functionality without Windows-specific code.

=cut
