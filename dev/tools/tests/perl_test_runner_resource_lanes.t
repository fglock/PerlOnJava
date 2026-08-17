use strict;
use warnings;

use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $runner = File::Spec->catfile(
    $root, 'dev', 'tools', 'perl_test_runner.pl');
my $temporary = tempdir(CLEANUP => 1);
my $fake_jperl = File::Spec->catfile($temporary, 'fake-jperl');
my $regex_dir = File::Spec->catdir($temporary, 'perl5_t', 't', 're');
make_path($regex_dir);

write_file($fake_jperl, <<'FAKE_JPERL');
#!/usr/bin/env perl
exec $^X, @ARGV;
FAKE_JPERL
chmod 0755, $fake_jperl or die "chmod $fake_jperl failed: $!";

for my $name (qw(pat_psycho.t pat_psycho_thr.t speed.t speed_thr.t)) {
    write_file(File::Spec->catfile($regex_dir, $name), <<'CPU_TEST');
use strict;
use warnings;
use Time::HiRes qw(time sleep);

my $log = $ENV{RUNNER_RESOURCE_LANE_LOG} or die "missing lane log\n";
open my $start, '>>', $log or die "cannot append $log: $!";
print {$start} "start $$ ", time(), "\n";
close $start;
sleep 0.5;
open my $end, '>>', $log or die "cannot append $log: $!";
print {$end} "end $$ ", time(), "\n";
close $end;
print "1..1\nok 1 - completed\n";
CPU_TEST
}

my $lane_log = File::Spec->catfile($temporary, 'lane.log');
local $ENV{RUNNER_RESOURCE_LANE_LOG} = $lane_log;
open my $command, '-|', $^X, $runner,
    '--jperl', $fake_jperl,
    '--jobs', '1',
    '--cpu-heavy-jobs', '2',
    '--timeout', '5',
    $regex_dir
    or die "cannot start test runner: $!";
my $output = do { local $/; <$command> };
ok(close $command, 'resource-lane fixture completes') or diag($output // '');
like($output, qr/4 CPU-heavy tests use 2 jobs/,
    'runner reports the dedicated CPU-heavy lane');

open my $log_fh, '<', $lane_log or die "cannot read $lane_log: $!";
my (%start, %end);
while (<$log_fh>) {
    my ($event, $pid, $time) = split;
    ($event eq 'start' ? \%start : \%end)->{$pid} = $time;
}
close $log_fh;
is(scalar keys %start, 4, 'all CPU-heavy fixtures started');
is(scalar keys %end, 4, 'all CPU-heavy fixtures completed');

my @pids = keys %start;
my $overlap = 0;
for my $left (0 .. $#pids) {
    for my $right ($left + 1 .. $#pids) {
        next unless defined $pids[$right];
        my ($a, $b) = @pids[$left, $right];
        $overlap = 1 if $start{$a} < $end{$b} && $start{$b} < $end{$a};
    }
}
ok($overlap, 'at least two CPU-heavy fixtures run concurrently');

done_testing;

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>', $path or die "cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "cannot close $path: $!";
}
