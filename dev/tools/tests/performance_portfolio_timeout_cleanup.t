use strict;
use warnings;

use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use Test::More;
use Time::HiRes qw(time);

plan skip_all => 'private POSIX process groups are unavailable on Windows'
    if $^O eq 'MSWin32';

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $runner = File::Spec->catfile($root, 'dev', 'bench', 'run_performance_portfolio.pl');
my $temporary = tempdir(CLEANUP => 1);
my $fake_jperl = File::Spec->catfile($temporary, 'fake-jperl');
my $pid_file = File::Spec->catfile($temporary, 'writer.pid');
my $output_dir = File::Spec->catdir($temporary, 'results');

open my $fake, '>:raw', $fake_jperl or die "cannot write fake launcher: $!";
print {$fake} <<'FAKE_JPERL';
#!/usr/bin/env perl
use strict;
use warnings;
my $pid = fork();
die "fork failed: $!" unless defined $pid;
if ($pid == 0) {
    $SIG{TERM} = 'IGNORE';
    open my $fh, '>:raw', $ENV{PORTFOLIO_WRITER_PID} or die $!;
    print {$fh} "$$\n";
    close $fh;
    sleep 60;
    exit 0;
}
$SIG{TERM} = sub { exit 0 };
sleep 60;
FAKE_JPERL
close $fake or die "cannot close fake launcher: $!";
chmod 0755, $fake_jperl or die "cannot chmod fake launcher: $!";

my $started = time();
local $ENV{PORTFOLIO_WRITER_PID} = $pid_file;
open my $command, '-|', 'timeout', '15', $^X, $runner,
    '--jperl', $fake_jperl,
    '--workload', 'closure', '--pairs', '1', '--timeout', '1',
    '--warmup-min', '1', '--warmup-max', '1', '--windows', '1',
    '--output-dir', $output_dir
    or die "cannot start portfolio runner: $!";
my $output = do { local $/; <$command> };
ok(!close $command, 'timed-out reader makes the portfolio fail');
my $elapsed = time() - $started;
cmp_ok($elapsed, '<', 10, 'coordinator returns promptly after its reader exits');

open my $pid_handle, '<', $pid_file or die "fake descendant did not record its PID";
chomp(my $writer_pid = <$pid_handle>);
close $pid_handle;
ok($writer_pid =~ /^\d+$/, 'orphan candidate recorded its PID');
ok(!kill(0, $writer_pid), 'private reader process group removes inherited pipe writer');

done_testing;
