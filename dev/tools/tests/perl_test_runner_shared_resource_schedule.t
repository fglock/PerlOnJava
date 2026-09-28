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
my $cpu_dir = File::Spec->catdir($temporary, 'perl5_t', 't', 're');
make_path($cpu_dir);

write_file($fake_jperl, <<'FAKE_JPERL');
#!/usr/bin/env perl
exec $^X, @ARGV;
FAKE_JPERL
chmod 0755, $fake_jperl or die "chmod $fake_jperl failed: $!";

my $log = File::Spec->catfile($temporary, 'schedule.log');
my $ordinary = File::Spec->catfile($temporary, 'ordinary.t');
my $cpu_heavy = File::Spec->catfile($cpu_dir, 'pat_psycho.t');
for my $test_file ($ordinary, $cpu_heavy) {
    write_file($test_file, <<'TEST');
use strict;
use warnings;
use Time::HiRes qw(time sleep);

my $log = $ENV{RUNNER_SHARED_SCHEDULE_LOG} or die "missing schedule log\n";
my $class = $0 =~ /pat_psycho\.t\z/ ? 'cpu-heavy' : 'ordinary';
open my $start, '>>', $log or die "cannot append $log: $!";
print {$start} "start $class ", time(), "\n";
close $start;
sleep 0.5;
open my $end, '>>', $log or die "cannot append $log: $!";
print {$end} "end $class ", time(), "\n";
close $end;
print "1..1\nok 1 - completed\n";
TEST
}

local $ENV{RUNNER_SHARED_SCHEDULE_LOG} = $log;
open my $command, '-|', $^X, $runner,
    '--jperl', $fake_jperl,
    '--jobs', '1',
    '--cpu-heavy-jobs', '1',
    '--timeout', '5',
    $ordinary, $cpu_heavy
    or die "cannot start test runner: $!";
my $output = do { local $/; <$command> };
ok(close $command, 'shared-resource fixture completes') or diag($output // '');
like($output, qr/within the shared scheduler/,
    'runner reports a shared scheduler rather than an exclusive CPU-heavy lane');

open my $log_fh, '<', $log or die "cannot read $log: $!";
my %event;
while (<$log_fh>) {
    my ($kind, $file, $time) = split;
    $event{$file}{$kind} = $time;
}
close $log_fh;

for my $class (qw(ordinary cpu-heavy)) {
    ok(defined $event{$class}{start}, "$class fixture started");
    ok(defined $event{$class}{end}, "$class fixture completed");
}
ok(
    $event{ordinary}{start} < $event{'cpu-heavy'}{end}
        && $event{'cpu-heavy'}{start} < $event{ordinary}{end},
    'ordinary and CPU-heavy fixtures overlap instead of running in exclusive phases',
);

done_testing;

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>', $path or die "cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "cannot close $path: $!";
}
