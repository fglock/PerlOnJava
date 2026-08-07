package PerlOnJava::Process;

use strict;
use warnings;
use Config ();
use Exporter qw(import);

our $VERSION = '0.01';
our @EXPORT_OK = qw(run_process);

if ($Config::Config{archname} =~ /^java-/) {
    require XSLoader;
    XSLoader::load(__PACKAGE__, $VERSION);
}

sub run_process {
    my (%args) = @_;
    my $argv = $args{argv};
    die "run_process requires a non-empty argv array reference"
        unless ref($argv) eq 'ARRAY' && @$argv;

    my $timeout = defined($args{timeout}) ? $args{timeout} : 0;
    my $cwd = defined($args{cwd}) ? $args{cwd} : '';
    return _run($timeout, $cwd, @$argv)
        if $Config::Config{archname} =~ /^java-/;

    require File::Temp;
    my ($capture, $capture_path) = File::Temp::tempfile(
        'perlonjava-process-XXXXXX', TMPDIR => 1, UNLINK => 1);
    my $pid = fork();
    die "fork failed: $!" unless defined $pid;
    if (!$pid) {
        chdir($cwd) or die "chdir $cwd: $!" if length $cwd;
        open STDOUT, '>&', $capture or die "redirect stdout: $!";
        open STDERR, '>&', $capture or die "redirect stderr: $!";
        exec { $argv->[0] } @$argv;
        die "exec $argv->[0]: $!";
    }

    my $deadline = $timeout > 0 ? time() + $timeout : 0;
    my $timed_out = 0;
    while (waitpid($pid, 1) == 0) {
        if ($deadline && time() >= $deadline) {
            $timed_out = 1;
            kill 'TERM', $pid;
            select undef, undef, undef, 0.1;
            kill 'KILL', $pid;
            waitpid($pid, 0);
            last;
        }
        select undef, undef, undef, 0.02;
    }
    my $status = $?;
    seek $capture, 0, 0;
    my $output = do { local $/; <$capture> };
    close $capture;
    return {
        exit_code => $timed_out || $status == -1 ? -1 : $status >> 8,
        output => defined($output) ? $output : '',
        timed_out => $timed_out,
        error => '',
    };
}

1;
