#!/usr/bin/env perl
use strict;
use warnings;
use File::Basename qw(dirname);
use File::Spec;
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir(dirname(__FILE__), '..', '..', '..'));
my $makefile = File::Spec->catfile($root, 'Makefile');
my $tester = File::Spec->catfile($root, 'dev', 'tools', 'cpan_random_tester.pl');

open my $mf, '<', $makefile or die "Cannot read $makefile: $!";
my $make_text = do { local $/; <$mf> };
close $mf;
open my $tf, '<', $tester or die "Cannot read $tester: $!";
my $tester_text = do { local $/; <$tf> };
close $tf;

my @modules = qw(PPR Catalyst Mojolicious Image::ExifTool DateTime Template DBIx::Class Excel::Writer::XLSX);
like($make_text, qr/^test-cpan-release-acceptance: build$/m,
    'release acceptance target builds before testing');
like($make_text, qr/--modules "\$\$modules".*--jobs 1 --strict-exit/s,
    'release target uses explicit modules, one test worker, and strict exit');
like($make_text, qr/one CPAN test worker at a time.*process slots/s,
    'release target documents why tests run without parallel workers');
like($make_text, qr/--process-snapshots --progress-interval 10/s,
    'release target records process capacity during the Mojolicious gate');
like($make_text, qr/ulimit -u.*sysctl kern\.maxproc kern\.maxprocperuid/s,
    'release log records the configured process limits');
like($make_text, qr/cgroup pids\.current.*cgroup pids\.max/s,
    'release log records Linux cgroup process limits when available');
like($make_text, qr/timeout 28800 perl dev\/tools\/cpan_random_tester\.pl/s,
    'release target has an outer timeout');
for my $module (@modules) {
    like($make_text, qr/\Q$module\E/, "release target includes $module");
}
like($tester_text, qr/'strict-exit'\s*=>\s*\\\$strict_exit/,
    'tester exposes strict exit mode');
like($tester_text, qr/'Catalyst'\s*=>\s*7200/,
    'Catalyst receives a two-hour test timeout');
like($tester_text, qr/Strict target failures:/,
    'tester reports strict target failures');
like($tester_text, qr/NOT TESTED \(already installed\/up to date\)/,
    'strict mode rejects an up-to-date target that was not tested');

like($tester_text, qr/Host process snapshot before target/,
    'tester records process capacity before each selected target');
like($tester_text, qr/Host process snapshot during target/,
    'tester samples process capacity while a target is running');
like($tester_text, qr/Host process snapshot after target/,
    'tester records process capacity after each selected target');

my ($snapshot_source) = $tester_text =~
    /(sub format_host_process_capacity_snapshot \{.*?)(?=\nsub )/s;
ok(defined $snapshot_source, 'extracted process-capacity snapshot formatter');
eval $snapshot_source;
die "cannot load process-capacity snapshot formatter: $@" if $@;
is(
    format_host_process_capacity_snapshot(
        ["501 100\n", "0 1\n", "501 101\n"], 501, 1,
    ),
    'total=3, uid=501, user_total=2',
    'process snapshot reports total and current-user process counts',
);
is(
    format_host_process_capacity_snapshot([], 501, 0),
    'unavailable (ps failed)',
    'process snapshot reports unavailable data when ps fails',
);

SKIP: {
    skip 'the ps command contract is POSIX-specific', 2 if $^O eq 'MSWin32';
    my ($snapshot_command_source) = $tester_text =~
        /(sub host_process_capacity_snapshot \{.*?)(?=\nsub )/s;
    ok(defined $snapshot_command_source, 'extracted process snapshot command');
    eval $snapshot_command_source;
    die "cannot load process snapshot command: $@" if $@;
    my $snapshot = host_process_capacity_snapshot();
    skip 'ps is unavailable in this environment', 1
        if $snapshot =~ /\Aunavailable/;
    like($snapshot, qr/\Atotal=\d+, uid=\d+, user_total=\d+\z/,
        'process snapshot reads current host counts with ps');
}

done_testing;
