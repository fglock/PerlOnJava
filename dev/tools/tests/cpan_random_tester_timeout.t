use strict;
use warnings;

use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IO::Select;
use POSIX qw(WNOHANG);
use Test::More;
use Time::HiRes qw(time sleep);

if ($^O eq 'MSWin32') {
    plan skip_all => 'process groups and lsof pipe discovery are Unix-specific';
}

my $has_lsof = system('lsof -v >/dev/null 2>&1') == 0;
plan skip_all => 'lsof is required to identify detached pipe writers' unless $has_lsof;

our ($max_runtime, $activity_grace, $progress_interval, $KILL_AFTER,
    $MAX_CAPTURE_BYTES, $interrupt_count);
$max_runtime = 1;
$activity_grace = 1;
$progress_interval = 0;
$KILL_AFTER = 1;
$MAX_CAPTURE_BYTES = 1_000_000;
$interrupt_count = 0;
$SIG{INT} = sub { $interrupt_count++ };

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools', 'cpan_random_tester.pl');
open my $fh, '<', $tool or die "cannot read $tool: $!";
my $source = do { local $/; <$fh> };
close $fh or die "cannot close $tool: $!";

for my $name (qw(
    run_with_timeout
    append_bounded_output
    write_log_chunk
    terminate_process_group
    cleanup_run_processes
    note_pipe_writer_pids
    note_run_processes
)) {
    my ($sub_source) = $source =~ /(sub \Q$name\E \{.*?)(?=\nsub |\n# ─|\z)/s;
    ok(defined $sub_source, "extracted $name from the CPAN tester");
    eval $sub_source;
    die "cannot load $name: $@" if $@;
}

# The timeout test deliberately models a child which exits after detaching a
# grandchild.  PPID-based cleanup alone cannot find that grandchild; it keeps
# the monitor's output pipe open exactly like CPAN.pm did in production.
sub note_run_descendants { }
sub command_label { return join ' ', @_ }

my $temporary = tempdir(CLEANUP => 1);
my $pid_file = File::Spec->catfile($temporary, 'detached.pid');

pipe(my $probe_reader, my $probe_writer) or die "probe pipe failed: $!";
my $probe_pid = fork();
die "probe fork failed: $!" unless defined $probe_pid;
if ($probe_pid == 0) {
    close $probe_reader;
    setpgrp(0, 0);
    sleep 60;
    exit 0;
}
close $probe_writer;
sleep 0.1;
my %pipe_writers;
note_pipe_writer_pids($probe_reader, \%pipe_writers);
ok($pipe_writers{$probe_pid}, 'pipe-writer discovery finds a detached process');
kill 9, $probe_pid;
close $probe_reader;

my $child_program = join '',
    'my $pid_file = shift; ',
    'my $child = fork(); die "fork failed: $!" unless defined $child; ',
    'exit 0 if $child; ',
    'setpgrp(0, 0); ',
    'open my $fh, ">", $pid_file or die "open: $!"; ',
    'print {$fh} "$$\\n"; close $fh; ',
    '$SIG{TERM} = "IGNORE"; sleep 60;';

my $started = time();
my (undef, $timed_out, $error) = run_with_timeout(
    [$^X, '-e', $child_program, $pid_file], 1, undef, 1,
);
my $elapsed = time() - $started;

ok($timed_out, 'detached pipe writer reaches the hard cap');
like($error, qr/runtime >1s/, 'hard-cap timeout is reported');
cmp_ok($elapsed, '<', 5,
    'hard cap returns promptly instead of waiting for the detached pipe writer');

open my $pid_fh, '<', $pid_file or die "cannot read detached PID: $!";
chomp(my $detached_pid = <$pid_fh>);
close $pid_fh;
ok($detached_pid =~ /^\d+$/, 'detached child recorded its PID');
ok(!kill(0, $detached_pid), 'detached pipe writer was killed at the hard cap');

# Ctrl-C is delivered to the supervisor, not the isolated command process
# group. The supervisor must terminate and reap that process tree before it
# exits instead of leaving the CPAN command running in the background.
my $interrupt_kid_file = File::Spec->catfile($temporary, 'interrupt-kid.pid');
my $interrupt_child = join '',
    'my $pid_file = shift; ',
    '$SIG{TERM} = "IGNORE"; ',
    'open my $fh, ">", $pid_file or die "open: $!"; ',
    'print {$fh} "$$\\n"; close $fh; ',
    'sleep 60;';
my $signaler = fork();
die "signal fork failed: $!" unless defined $signaler;
if ($signaler == 0) {
    sleep 1;
    kill 'INT', getppid();
    exit 0;
}
my $interrupt_started = time();
my $did_return = eval {
    run_with_timeout([$^X, '-e', $interrupt_child, $interrupt_kid_file], 60, undef, 60);
    1;
};
my $interrupt_error = $@;
waitpid($signaler, 0);
ok(!$did_return, 'Ctrl-C aborts the monitored command');
like($interrupt_error, qr/Interrupted by SIGINT/, 'Ctrl-C is reported after cleanup');
cmp_ok(time() - $interrupt_started, '<', 6,
    'interrupt cleanup kills a command which ignores TERM');
open my $interrupt_pid_fh, '<', $interrupt_kid_file
    or die "cannot read interrupted child PID: $!";
chomp(my $interrupt_kid_pid = <$interrupt_pid_fh>);
close $interrupt_pid_fh;
ok(!kill(0, $interrupt_kid_pid), 'interrupted command process was killed');

done_testing;
