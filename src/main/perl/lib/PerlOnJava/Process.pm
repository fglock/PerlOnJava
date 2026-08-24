package PerlOnJava::Process;

use strict;
use warnings;
use Config ();
use Exporter qw(import);

our $VERSION = '0.01';
our @EXPORT_OK = qw(run_process);

sub _is_perlonjava_runtime {
    return $Config::Config{archname} =~ /^java-/
        || $^X =~ m{(?:^|[\\/])jperl(?:\.bat)?\z}i
        || (defined $ENV{PERLONJAVA_EXECUTABLE}
            && length $ENV{PERLONJAVA_EXECUTABLE});
}

if (_is_perlonjava_runtime()) {
    require XSLoader;
    XSLoader::load(__PACKAGE__, $VERSION);
}

sub run_process {
    my (%args) = @_;
    my $argv = $args{argv};
    die "run_process requires a non-empty argv array reference"
        unless ref($argv) eq 'ARRAY' && @$argv;

    my $timeout = defined($args{timeout}) ? $args{timeout} : 0;
    my $cwd;
    if (defined $args{cwd}) {
        $cwd = $args{cwd};
    } else {
        require Cwd;
        $cwd = Cwd::getcwd();
        $cwd = '' unless defined $cwd;
    }
    my $tee = $args{tee} ? 1 : 0;
    return _run($timeout, $cwd, $tee, @$argv)
        if _is_perlonjava_runtime();

    require IO::Select;
    pipe my $reader, my $writer or die "pipe failed: $!";
    my $pid = fork();
    die "fork failed: $!" unless defined $pid;
    if (!$pid) {
        close $reader;
        chdir($cwd) or die "chdir $cwd: $!" if length $cwd;
        open STDOUT, '>&', $writer or die "redirect stdout: $!";
        open STDERR, '>&', $writer or die "redirect stderr: $!";
        close $writer;
        exec { $argv->[0] } @$argv;
        die "exec $argv->[0]: $!";
    }
    close $writer;

    my $deadline = $timeout > 0 ? time() + $timeout : 0;
    my $timed_out = 0;
    my $status;
    my $child_done = 0;
    my $output = '';
    my $select = IO::Select->new($reader);
    while (1) {
        for my $ready ($select->can_read(0.02)) {
            my $read = sysread($ready, my $chunk, 8192);
            if (defined($read) && $read > 0) {
                $output .= $chunk;
                if ($tee) {
                    print STDOUT $chunk;
                    STDOUT->flush;
                }
            } elsif (defined $read) {
                $select->remove($ready);
                close $ready;
            }
        }

        unless ($child_done) {
            my $waited = waitpid($pid, 1);
            if ($waited == $pid) {
                $status = $?;
                $child_done = 1;
                last unless $select->count;
            }
        }
        if (!$child_done && $deadline && time() >= $deadline) {
            $timed_out = 1;
            kill 'TERM', $pid;
            select undef, undef, undef, 0.1;
            kill 'KILL', $pid;
            waitpid($pid, 0);
            $status = $?;
            last;
        }
    }
    close $reader if $select->count;
    $status = -1 unless defined $status;
    return {
        exit_code => $timed_out || $status == -1 ? -1 : $status >> 8,
        output => $output,
        timed_out => $timed_out,
        error => '',
    };
}

1;
