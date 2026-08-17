use strict;
use warnings;

use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

if ($^O eq 'MSWin32' || $^O eq 'cygwin' || $^O eq 'msys') {
    plan skip_all => 'POSIX process groups are not available';
}

my $timeout_program = system('which timeout >/dev/null 2>&1') == 0
    || system('which gtimeout >/dev/null 2>&1') == 0;
plan skip_all => 'GNU timeout is required' unless $timeout_program;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $runner = File::Spec->catfile(
    $root, 'dev', 'tools', 'perl_test_runner.pl');
my $temporary = tempdir(CLEANUP => 1);
my $fake_jperl = File::Spec->catfile($temporary, 'fake-jperl');
my $test_file = File::Spec->catfile($temporary, 'nested-timeout.t');
my $pid_file = File::Spec->catfile($temporary, 'child.pid');
my $json_file = File::Spec->catfile($temporary, 'result.json');

write_file($fake_jperl, <<'FAKE_JPERL');
#!/usr/bin/env perl
exec $^X, @ARGV;
FAKE_JPERL
chmod 0755, $fake_jperl or die "chmod $fake_jperl failed: $!";

write_file($test_file, <<'NESTED_TEST');
use strict;
use warnings;

my $pidfile = $ENV{RUNNER_CHILD_PID_FILE}
    or die "RUNNER_CHILD_PID_FILE is required\n";
my $child = fork();
die "fork failed: $!\n" unless defined $child;
if ($child == 0) {
    $SIG{TERM} = 'IGNORE';
    open my $fh, '>', $pidfile or die "cannot write $pidfile: $!\n";
    print {$fh} "$$\n";
    close $fh;
    sleep 60;
    exit 0;
}
waitpid($child, 0);
NESTED_TEST

local $ENV{RUNNER_CHILD_PID_FILE} = $pid_file;
open my $command, '-|', $^X, $runner,
    '--jperl', $fake_jperl,
    '--jobs', '1',
    '--timeout', '1',
    '--output', $json_file,
    $test_file
    or die "cannot start test runner: $!";
my $runner_output = do { local $/; <$command> };
ok(close $command, 'runner reports the timed test without an infrastructure error')
    or diag($runner_output // '');

open my $json_fh, '<:raw', $json_file or die "cannot read $json_file: $!";
my $document = JSON::PP->new->utf8->decode(do { local $/; <$json_fh> });
close $json_fh;
my ($result) = values %{$document->{results}};
is($result->{status}, 'timeout', 'nested process test reaches the hard timeout');

open my $pid_fh, '<', $pid_file or die "cannot read $pid_file: $!";
chomp(my $child_pid = <$pid_fh>);
close $pid_fh;
ok($child_pid =~ /^\d+$/, 'nested child recorded its PID');

# Signal zero checks existence without changing process state. The child
# ignores TERM, so this proves the final session-group KILL reached it.
ok(!kill(0, $child_pid), 'nested timeout child was not orphaned');

done_testing;

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>', $path or die "cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "cannot close $path: $!";
}
