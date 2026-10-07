#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use IPC::Open3;
use Symbol qw(gensym);
use AnyEvent;
use File::Temp qw(tempdir);
use File::Spec;

plan skip_all => 'SIGCHLD is not supported on native Windows'
    if $^O eq 'MSWin32';
plan tests => 3;

# A nested Perl child exercises the child watcher and the stdout drain used by
# AnyEvent::Open3::Simple.  A shell child or a Perl -e child does not cover
# the file execution path used by the upstream distribution's tests.
my $dir = tempdir(CLEANUP => 1);
my $child_script = File::Spec->catfile($dir, 'child.pl');
open my $script_fh, '>', $child_script or die "cannot create child script: $!";
print {$script_fh} "#!$^X\nprint qq(child-output\\n); exit 3;\n";
close $script_fh or die "cannot close child script: $!";

my ($stdin, $stdout, $stderr) = (undef, undef, gensym);
my $pid = open3(
    $stdin, $stdout, $stderr,
    $^X, $child_script
);
close $stdin;

my $done = AnyEvent->condvar;
my ($callback_pid, $status, $output);
my $child_watcher = AnyEvent->child(
    pid => $pid,
    cb => sub {
        ($callback_pid, $status) = @_;
        local $/;
        $output = <$stdout>;
        $done->send;
    },
);
my $timeout = AnyEvent->timer(after => 5, cb => sub { $done->send });

$done->recv;

is($callback_pid, $pid, 'child watcher reports the nested Perl child');
is(defined($status) ? ($status >> 8) : undef, 3,
    'child watcher reports the child exit status');
is($output, "child-output\n", 'child stdout is available in the watcher callback');

# Reap a child if a failed watcher left it running.  On the success path the
# watcher has already collected the status and waitpid returns -1 immediately.
if (!defined $callback_pid) {
    kill 'KILL', $pid;
    waitpid($pid, 0);
}
close $stdout;
close $stderr;
