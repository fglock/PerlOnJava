#!/usr/bin/perl

use strict;
use warnings;

use Encode qw(decode FB_DEFAULT);
use JSON::PP ();
use File::Spec ();

my ($report_dir, $prefix) = @ARGV;
die "Usage: $0 <report-directory> <report-prefix>\n"
    unless defined $report_dir && defined $prefix && @ARGV == 2;

my %pairs = (
    'perl5_t/t/re/pat_re_eval.t'          => 'perl5_t/t/re/pat_re_eval_thr.t',
    'perl5_t/t/re/pat_rt_report.t'        => 'perl5_t/t/re/pat_rt_report_thr.t',
    'perl5_t/t/re/pat_special_cc.t'       => 'perl5_t/t/re/pat_special_cc_thr.t',
    'perl5_t/t/re/reg_email.t'            => 'perl5_t/t/re/reg_email_thr.t',
    'perl5_t/t/re/regexp_unicode_prop.t'  => 'perl5_t/t/re/regexp_unicode_prop_thr.t',
    'perl5_t/t/re/speed.t'                => 'perl5_t/t/re/speed_thr.t',
    'perl5_t/t/re/pat.t'                  => 'perl5_t/t/re/pat_thr.t',
    'perl5_t/t/re/pat_advanced.t'         => 'perl5_t/t/re/pat_advanced_thr.t',
    'perl5_t/t/re/pat_psycho.t'           => 'perl5_t/t/re/pat_psycho_thr.t',
    'perl5_t/t/re/regexp_qr_embed.t'      => 'perl5_t/t/re/regexp_qr_embed_thr.t',
);

my @standalone_wrappers = (
    'perl5_t/t/re/stclass_threads.t',
    'perl5_t/t/re/user_prop_race_thr.t',
);

my @direct_reports = (
    File::Spec->catfile($report_dir, "$prefix-direct.json"),
    sort glob(File::Spec->catfile($report_dir, "$prefix-direct-*.json")),
);
my @wrapper_reports = (
    File::Spec->catfile($report_dir, "$prefix-wrappers.json"),
    sort glob(File::Spec->catfile($report_dir, "$prefix-wrapper-*.json")),
);

my $direct = load_results(@direct_reports);
my $wrapper = load_results(@wrapper_reports);
my @failures;

for my $direct_name (sort keys %pairs) {
    my $wrapper_name = $pairs{$direct_name};
    my $before = $direct->{$direct_name};
    my $after = $wrapper->{$wrapper_name};

    unless ($before) {
        push @failures, "$direct_name: direct result is missing";
        next;
    }
    unless ($after) {
        push @failures, "$wrapper_name: wrapper result is missing";
        next;
    }

    compare_pair($direct_name, $before, $wrapper_name, $after, \@failures);
}

for my $wrapper_name (@standalone_wrappers) {
    my $result = $wrapper->{$wrapper_name};
    unless ($result) {
        push @failures, "$wrapper_name: standalone wrapper result is missing";
        next;
    }
    push @failures, "$wrapper_name: expected pass, got " . value($result, 'status')
        unless value($result, 'status') eq 'pass';
}

if (@failures) {
    print STDERR "Thread core parity FAILED for $prefix:\n";
    print STDERR "  - $_\n" for @failures;
    exit 1;
}

print "Thread core parity passed for $prefix (10 direct/wrapper pairs, "
    . scalar(@standalone_wrappers) . " standalone wrappers)\n";
exit 0;

sub compare_pair {
    my ($direct_name, $direct, $wrapper_name, $wrapper, $failures) = @_;
    my $label = "$direct_name -> $wrapper_name";

    for my $fatal_status (qw(error timeout)) {
        push @$failures, "$label: direct status is $fatal_status; parity cannot be established"
            if value($direct, 'status') eq $fatal_status;
        push @$failures, "$label: wrapper status is $fatal_status"
            if value($wrapper, 'status') eq $fatal_status;
    }

    my @not_worse = (
        [ok_count         => 'at least'],
        [actual_tests_run => 'at least'],
    );
    for my $comparison (@not_worse) {
        my ($field, $wording) = @$comparison;
        my $direct_value = number($direct, $field);
        my $wrapper_value = number($wrapper, $field);
        push @$failures,
            "$label: $field is $wrapper_value; expected $wording $direct_value"
            if $wrapper_value < $direct_value;
    }

    for my $field (qw(not_ok_count incomplete_tests)) {
        my $direct_value = number($direct, $field);
        my $wrapper_value = number($wrapper, $field);
        push @$failures,
            "$label: $field is $wrapper_value; direct result is $direct_value"
            if $wrapper_value > $direct_value;
    }

    my $direct_plan = number($direct, 'planned_tests');
    my $wrapper_plan = number($wrapper, 'planned_tests');
    push @$failures,
        "$label: planned_tests changed from $direct_plan to $wrapper_plan"
        if $wrapper_plan != $direct_plan;

    if (value($direct, 'status') eq 'pass'
            && value($wrapper, 'status') ne 'pass') {
        push @$failures,
            "$label: passing direct test became " . value($wrapper, 'status');
    }
}

sub load_results {
    my @files = @_;
    my %results;
    for my $file (@files) {
        die "Required report is missing: $file\n" unless -f $file;
        open my $fh, '<:raw', $file or die "Cannot read $file: $!\n";
        local $/;
        my $bytes = <$fh>;
        close $fh or die "Cannot close $file: $!\n";

        # Some upstream diagnostics contain isolated legacy bytes. They are
        # irrelevant to the numeric parity contract, so replace malformed
        # sequences before decoding the runner's otherwise-valid JSON.
        my $text = decode('UTF-8', $bytes, FB_DEFAULT);
        my $document = JSON::PP->new->utf8(0)->decode($text);
        my $file_results = $document->{results}
            or die "Report has no results object: $file\n";
        @results{keys %$file_results} = values %$file_results;
    }
    return \%results;
}

sub number {
    my ($result, $field) = @_;
    return 0 unless defined $result->{$field};
    return 0 + $result->{$field};
}

sub value {
    my ($result, $field) = @_;
    return defined $result->{$field} ? "$result->{$field}" : '';
}
