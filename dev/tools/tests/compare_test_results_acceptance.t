use strict;
use warnings;

use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools', 'compare_test_results.pl');
my $legacy_tool = File::Spec->catfile($root, 'dev', 'tools', 'compare_test_logs.pl');
my $temporary = tempdir(CLEANUP => 1);

subtest 'progress with complete direct and thread records passes' => sub {
    my $baseline = write_json('baseline.json', {
        'perl5_t/t/re/pat.t' => result(8, 10, 'partial'),
        'perl5_t/t/re/pat_thr.t' => result(8, 10, 'partial'),
    });
    my $candidate = write_json('progress.json', {
        'perl5_t/t/re/pat.t' => result(9, 10, 'partial'),
        'perl5_t/t/re/pat_thr.t' => result(10, 10, 'pass'),
    });
    my ($status, $output, $report) = run_strict($baseline, $candidate, 'progress-report.json');
    is($status, 0, 'strict comparison accepts progress');
    like($output, qr/regressions=0 improvements=2 missing=0/, 'summary reports progress');
    is_deeply([sort map { $_->{file} } @{$report->{improvements}}],
        [qw(perl5_t/t/re/pat.t perl5_t/t/re/pat_thr.t)],
        'direct and thread files remain separate identities');
};

subtest 'each invalid candidate class exits nonzero' => sub {
    my $baseline = write_json('invalid-baseline.json', {
        'perl5_t/t/re/a.t' => result(2, 2, 'pass'),
        'perl5_t/t/re/b.t' => result(2, 2, 'pass'),
    });
    my @cases = (
        [missing => {'perl5_t/t/re/a.t' => result(2, 2, 'pass')}, qr/MISSING FILES/],
        [regression => {
            'perl5_t/t/re/a.t' => result(1, 2, 'partial'),
            'perl5_t/t/re/b.t' => result(2, 2, 'pass'),
        }, qr/REGRESSIONS/],
        [timeout => {
            'perl5_t/t/re/a.t' => result(2, 2, 'pass'),
            'perl5_t/t/re/b.t' => result(0, 0, 'timeout', exit_code => 124),
        }, qr/EXECUTION ISSUES/],
        [error => {
            'perl5_t/t/re/a.t' => result(2, 2, 'pass'),
            'perl5_t/t/re/b.t' => result(0, 0, 'error', exit_code => 1),
        }, qr/status=error/],
        [zero_tap => {
            'perl5_t/t/re/a.t' => result(2, 2, 'pass'),
            'perl5_t/t/re/b.t' => result(0, 0, 'pass'),
        }, qr/ZERO TAP/],
        [truncated => {
            'perl5_t/t/re/a.t' => result(2, 2, 'pass'),
            'perl5_t/t/re/b.t' => result(1, 2, 'incomplete', actual_tests_run => 1,
                planned_tests => 2, incomplete_tests => 1),
        }, qr/TRUNCATED OR INCOMPLETE TAP/],
    );
    for my $case (@cases) {
        my ($name, $results, $diagnostic) = @$case;
        my $candidate = write_json("$name.json", $results);
        my ($status, $output) = run_strict($baseline, $candidate, "$name-report.json");
        is($status, 1, "$name is rejected");
        like($output, $diagnostic, "$name has an explicit diagnostic");
    }
};

subtest 'broad map rejects only newly invalid rows' => sub {
    my $baseline = write_json('broad-baseline.json', {
        'perl5_t/t/porting/inherited.t' => result(0, 0, 'error', exit_code => 1),
        'perl5_t/t/re/valid.t' => result(2, 2, 'pass'),
    });
    my $unchanged = write_json('broad-unchanged.json', {
        'perl5_t/t/porting/inherited.t' => result(0, 0, 'error', exit_code => 1),
        'perl5_t/t/re/valid.t' => result(2, 2, 'pass'),
    });
    my ($status, $output, $report) = run_no_new_invalid(
        $baseline, $unchanged, 'broad-unchanged-report.json');
    is($status, 0, 'an unchanged inherited invalid row is retained as broad evidence');
    is(scalar @{$report->{inherited_invalid}}, 1,
        'report classifies the inherited invalid row');
    is(scalar @{$report->{new_invalid}}, 0, 'report has no newly invalid row');
    like($output, qr/inherited-invalid=1/, 'human summary preserves inherited count');

    my $new_invalid = write_json('broad-new-invalid.json', {
        'perl5_t/t/porting/inherited.t' => result(0, 0, 'error', exit_code => 1),
        'perl5_t/t/re/valid.t' => result(0, 0, 'timeout', exit_code => 124),
    });
    ($status, $output, $report) = run_no_new_invalid(
        $baseline, $new_invalid, 'broad-new-invalid-report.json');
    is($status, 1, 'a valid baseline row becoming invalid is rejected');
    is_deeply([map { $_->{file} } @{$report->{new_invalid}}],
        ['perl5_t/t/re/valid.t'], 'new invalid report identifies the row');
    like($output, qr/NEW INVALID ROWS/, 'new invalid row has an explicit diagnostic');
};

subtest 'exact PR-958 artifacts normalize without hiding real loss' => sub {
    my $baseline = write_log('pr958.log', <<'BASELINE');
[  1/3] perl5_t/t/op/do.t ... x 94/99 ok (1.00s)
[  2/3] perl5_t/t/japh/abigail.t ... x 110/130 ok (1.00s)
[  3/3] perl5_t/t/op/real.t ... x 7/10 ok (1.00s)
BASELINE
    my $candidate = write_log('pr958-new.log', <<'CANDIDATE');
[  1/3] perl5_t/t/op/do.t ... x 69/71 ok (1.00s)
[  2/3] perl5_t/t/japh/abigail.t ... x 109/130 ok (1.00s)
[  3/3] perl5_t/t/op/real.t ... x 6/10 ok (1.00s)
CANDIDATE
    my ($status, $output, $report) = run_strict($baseline, $candidate,
        'pr958-report.json', '--normalize-pr958-artifacts');
    is($status, 1, 'unlisted real loss still fails');
    is($report->{summary}{baseline_ok}, 184, 'baseline uses normalized pass counts');
    is_deeply([map { $_->{file} } @{$report->{regressions}}],
        ['perl5_t/t/op/real.t'], 'only the real regression remains');
    like($output, qr/op\/real\.t/, 'human report names the real regression');
};

subtest 'malformed input is rejected' => sub {
    my $baseline = write_json('malformed-baseline.json', {
        'perl5_t/t/re/a.t' => result(1, 1, 'pass'),
    });
    my $malformed = write_log('malformed.log', "not a runner log\n");
    my ($status, $output) = run_strict($baseline, $malformed, 'malformed-report.json');
    isnt($status, 0, 'malformed input exits nonzero');
    like($output, qr/contains no per-file result lines/, 'parse failure is explicit');
};

subtest 'compare_test_logs exposes the fail-closed acceptance gate' => sub {
    my $baseline = write_json('wrapper-baseline.json', {
        'perl5_t/t/re/a.t' => result(2, 2, 'pass'),
    });
    my $candidate = write_json('wrapper-candidate.json', {
        'perl5_t/t/re/a.t' => result(1, 2, 'partial'),
    });
    my $report = File::Spec->catfile($temporary, 'wrapper-report.json');
    my @command = ($^X, $legacy_tool, '--strict-acceptance', '--output', $report,
        $baseline, $candidate);
    my $output = qx{@command 2>&1};
    is($? >> 8, 1, 'compatibility entrypoint propagates gate failure');
    like($output, qr/REGRESSIONS/, 'compatibility entrypoint emits human evidence');
    ok(-s $report, 'compatibility entrypoint writes JSON evidence');
};

done_testing;

sub result {
    my ($ok, $total, $status, %extra) = @_;
    return {
        ok_count => $ok,
        total_tests => $total,
        status => $status,
        planned_tests => $extra{planned_tests} // $total,
        actual_tests_run => $extra{actual_tests_run} // $total,
        incomplete_tests => $extra{incomplete_tests} // 0,
        exit_code => $extra{exit_code} // 0,
    };
}

sub write_json {
    my ($name, $results) = @_;
    return write_log($name, JSON::PP->new->canonical->pretty->encode({results => $results}));
}

sub write_log {
    my ($name, $contents) = @_;
    my $path = File::Spec->catfile($temporary, $name);
    open my $fh, '>:raw', $path or die "cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "cannot close $path: $!";
    return $path;
}

sub run_strict {
    my ($baseline, $candidate, $report_name, @extra) = @_;
    my $report_path = File::Spec->catfile($temporary, $report_name);
    my @command = ($^X, $tool, '--fail-on-regression', '--fail-on-invalid',
        '--output', $report_path, @extra, $baseline, $candidate);
    my $output = qx{@command 2>&1};
    my $status = $? >> 8;
    my $report;
    if (-e $report_path) {
        open my $fh, '<:raw', $report_path or die "cannot read $report_path: $!";
        $report = JSON::PP->new->decode(do { local $/; <$fh> });
        close $fh;
    }
    return ($status, $output, $report);
}

sub run_no_new_invalid {
    my ($baseline, $candidate, $report_name, @extra) = @_;
    my $report_path = File::Spec->catfile($temporary, $report_name);
    my @command = ($^X, $tool, '--fail-on-regression', '--fail-on-new-invalid',
        '--output', $report_path, @extra, $baseline, $candidate);
    my $output = qx{@command 2>&1};
    my $status = $? >> 8;
    my $report;
    if (-e $report_path) {
        open my $fh, '<:raw', $report_path or die "cannot read $report_path: $!";
        $report = JSON::PP->new->decode(do { local $/; <$fh> });
        close $fh;
    }
    return ($status, $output, $report);
}
