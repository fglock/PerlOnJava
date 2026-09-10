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
    $MAX_CAPTURE_BYTES);
$max_runtime = 1;
$activity_grace = 1;
$progress_interval = 0;
$KILL_AFTER = 1;
$MAX_CAPTURE_BYTES = 1_000_000;

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
    note_pipe_writer_pids
    note_run_processes
    cleanup_run_processes
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

done_testing;
