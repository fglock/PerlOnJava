#!/usr/bin/env perl

use strict;
use warnings;

use Digest::SHA qw(sha256_hex);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin qw($Bin);
use IO::Select;
use POSIX qw(_exit);
use Test::More;
use Time::HiRes qw(sleep time);

my $tools = File::Spec->catdir($Bin, '..');
my $lib = File::Spec->catdir($tools, 'lib');
use lib File::Spec->catdir($Bin, '..', 'lib');
use PerlOnJava::Phase36PerformanceEvidence qw(assert_tool_authority);

sub slurp {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    my $text = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!";
    return $text;
}

my $producer = slurp(File::Spec->catfile($tools,
    'run_phase36_final_performance.pl'));
my $ordinary = slurp(File::Spec->catfile($tools,
    'run_phase36_regex_performance.pl'));
my $evaluator = slurp(File::Spec->catfile($lib, 'PerlOnJava',
    'Phase36PerformanceEvidence.pm'));

my $authority_calls = () = $producer =~ /assert_tool_authority\(/g;
is($authority_calls, 2,
    'producer revalidates sealed tool authority before sealing and publication');
like($producer,
    qr/assert_tool_authority\([^;]+\);\s*write_json_atomic\(\$final/s,
    'final publication is immediately preceded by tool-authority revalidation');
like($evaluator,
    qr/sealed \$identity_field differs from authority-selected executable/,
    'checker binds sealed tool identities to authority fields');

for my $case (
        ['orchestrator', $producer, 'run_bounded', 'terminate_process_group'],
        ['ordinary producer', $ordinary, 'collect_bounded',
            'terminate_process_group']) {
    my ($label, $source, $sub, $cleanup) = @$case;
    my ($body) = $source =~ /sub \Q$sub\E \{(.*?)(?=\nsub |\n1;)/s;
    ok(defined $body, "$label bounded runner is present");
    like($body // '', qr/my \$ok = eval \{.*(?:sys?open|open my).*sysread.*print/s,
        "$label guards creation, reads, and writes after group establishment");
    like($body // '', qr/my \$error = \$\@;\s*\Q$cleanup\E\(\$pid, \$leader_reaped\)/s,
        "$label unconditionally cleans its exact group after guarded execution");
}
my ($replay_body) = $evaluator =~
    /sub replay_sealed_jfr_metrics \{(.*?)(?=\nsub |\n1;)/s;
ok(defined $replay_body, 'JFR replay bounded runner is present');
like($replay_body // '', qr/my \$ok = eval \{.*sysread.*print/s,
    'JFR replay guards reads and writes after group establishment');
like($replay_body // '',
    qr/my \$error = \$\@;\s*terminate_group\(\$pid, \$leader_reaped\)/s,
    'JFR replay unconditionally cleans its exact group after guarded execution');

my $temporary = tempdir(CLEANUP => 1);
my $sealed_dir = File::Spec->catdir($temporary, 'sealed');
make_path($sealed_dir);
my (%identity, %selected, %expected);
for my $spec ([git_executable => 'git'], [ps_executable => 'ps'],
        [uptime_executable => 'uptime']) {
    my ($identity_field, $selected_field) = @$spec;
    my $bytes = "$selected_field-authority-v1\n";
    my $selected_path = File::Spec->catfile($temporary, "$selected_field-bin");
    my $sealed_path = File::Spec->catfile($sealed_dir, $selected_field);
    for my $path ($selected_path, $sealed_path) {
        open my $fh, '>:raw', $path or die $!;
        print {$fh} $bytes or die $!;
        close $fh or die $!;
        chmod 0700, $path or die $!;
    }
    my $hash = sha256_hex($bytes);
    $selected{$selected_field} = $selected_path;
    $expected{$identity_field} = $hash;
    $identity{$identity_field} = {
        path => File::Spec->catfile('sealed', $selected_field),
        sha256 => $hash,
    };
}
ok(assert_tool_authority(\%identity, $temporary, \%selected, \%expected),
    'matching selected and sealed tool bytes are accepted');
open my $changed, '>>:raw', $selected{git} or die $!;
print {$changed} "changed\n" or die $!;
close $changed or die $!;
my $mutation_ok = eval {
    assert_tool_authority(\%identity, $temporary, \%selected, \%expected);
    1;
};
ok(!$mutation_ok, 'selected tool mutation before publication is rejected');
like($@, qr/executable changed before publication/,
    'tool mutation rejection identifies the publication race');

SKIP: {
    skip 'Unix process-group contract is intentionally fail-closed on Windows', 5
        if $^O eq 'MSWin32';
    pipe my $output_reader, my $output_writer or die $!;
    pipe my $life_reader, my $life_writer or die $!;
    my $leader = fork();
    die "fork failed: $!" unless defined $leader;
    if ($leader == 0) {
        close $output_reader;
        close $life_reader;
        POSIX::setpgid(0, 0) == 0 or _exit(120);
        my $descendant = fork();
        _exit(121) unless defined $descendant;
        if ($descendant == 0) {
            close $output_writer;
            $SIG{TERM} = 'IGNORE';
            syswrite($life_writer, "ready\n");
            sleep 30;
            _exit(0);
        }
        close $life_writer;
        close $output_writer;
        _exit(0);
    }
    close $output_writer;
    close $life_writer;
    eval { POSIX::setpgid($leader, $leader) };
    waitpid($leader, 0);
    is($?, 0, 'process-group leader exits successfully');
    my $output_count = sysread($output_reader, my $output, 1);
    is($output_count, 0,
        'detached descendant closes inherited bounded-output descriptor');
    is(sysread($life_reader, my $ready, 6), 6,
        'closed-pipe descendant remains alive after leader exit');
    is($ready, "ready\n", 'descendant lifetime signal is complete');
    PerlOnJava::Phase36PerformanceEvidence::terminate_group($leader, 1);
    my $selector = IO::Select->new($life_reader);
    my $deadline = time() + 3;
    my $eof;
    while (time() < $deadline && !defined $eof) {
        if ($selector->can_read(0.05)) {
            my $count = sysread($life_reader, my $discard, 1);
            $eof = 1 if defined($count) && $count == 0;
        }
    }
    ok($eof, 'unconditional cleanup kills closed-pipe surviving descendant');
    close $output_reader;
    close $life_reader;
}

done_testing();
