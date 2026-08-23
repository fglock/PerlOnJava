#!/usr/bin/env perl

use strict;
use warnings;

use Digest::SHA;
use File::Basename qw(dirname);
use File::Spec;
use File::Temp qw(tempfile);
use FindBin qw($Bin);
use Getopt::Long qw(GetOptions);
use JSON::PP;

use lib File::Spec->catdir($Bin, 'lib');
use PerlOnJava::Phase36PerformanceEvidence qw(
    evaluate_performance load_json policy_sha256
);

my $requirements = File::Spec->catfile($Bin,
    'phase36_acceptance_requirements.json');
my ($input, $output, $java, $perl, $authority_key, $baseline_source,
    $candidate_source, $perl5_source, $help);
GetOptions(
    'requirements=s' => \$requirements,
    'input=s' => \$input,
    'output=s' => \$output,
    'java=s' => \$java,
    'perl=s' => \$perl,
    'authority-key=s' => \$authority_key,
    'baseline-source=s' => \$baseline_source,
    'candidate-source=s' => \$candidate_source,
    'perl5-source=s' => \$perl5_source,
    'help' => \$help,
) or usage(2);
usage(0) if $help;
my $missing_required = grep { !defined($_) }
    ($input, $output, $java, $perl, $authority_key, $baseline_source,
        $candidate_source, $perl5_source);
usage(2) if @ARGV || $missing_required;
die "--perl does not identify the interpreter executing the assembler\n"
    unless -f $perl && -x $perl && -f $^X
        && file_sha256($perl) eq file_sha256($^X);
my $rules = load_json($requirements, 'performance requirements', 4 * 1024 * 1024);
my $document = load_json($input, 'performance input', 16 * 1024 * 1024);
my $root = dirname(File::Spec->rel2abs($input));
my $evaluation = evaluate_performance($document, $rules, $root, {
    java => $java,
    perl => $perl,
    authority_key => $authority_key,
    baseline_source => $baseline_source,
    candidate_source => $candidate_source,
    perl5_source => $perl5_source,
    orchestrator => File::Spec->catfile($Bin,
        'run_phase36_final_performance.pl'),
    ordinary_performance_producer => File::Spec->catfile($Bin,
        'run_phase36_regex_performance.pl'),
    performance_evaluator => File::Spec->catfile($Bin, 'lib', 'PerlOnJava',
        'Phase36PerformanceEvidence.pm'),
    benchmark => File::Spec->catfile($Bin, 'phase36_regex_benchmark.pl'),
    requirements => $requirements,
    jfr_metrics_producer => File::Spec->catfile($Bin, 'Phase36JfrMetrics.java'),
});

$document->{policy_sha256} = policy_sha256($rules);
$document->{evaluation} = $evaluation;
$document->{decision} = $evaluation->{decision};
$document->{verified} = $evaluation->{verified};
write_json_exclusive($output, $document);
print File::Spec->rel2abs($output), "\n";
exit($evaluation->{decision} eq 'passed' ? 0
    : $evaluation->{decision} eq 'review-stop' ? 2 : 1);

sub write_json_exclusive {
    my ($file, $value) = @_;
    my $directory = dirname(File::Spec->rel2abs($file));
    my ($fh, $staging) = tempfile('.phase36-final-XXXXXX',
        DIR => $directory, UNLINK => 0);
    chmod 0600, $staging or die "Cannot protect staging file $staging: $!\n";
    my $published;
    my $ok = eval {
        binmode $fh, ':raw';
        print {$fh} JSON::PP->new->utf8->canonical->pretty->encode($value)
            or die "Cannot write staging file $staging: $!\n";
        close $fh or die "Cannot close staging file $staging: $!\n";
        undef $fh;
        link $staging, $file
            or die "Cannot exclusively publish $file: $!\n";
        $published = 1;
        unlink $staging or die "Cannot remove staging file $staging: $!\n";
        1;
    };
    my $error = $@;
    close $fh if $fh;
    unlink $staging if -e $staging;
    die $error unless $ok;
    die "Atomic publication of $file did not complete\n" unless $published;
}

sub file_sha256 {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $hash = Digest::SHA->new(256)->addfile($fh)->hexdigest;
    close $fh or die "Cannot close $path: $!\n";
    return $hash;
}

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: assemble_phase36_final_performance.pl --input DRAFT.json --output FINAL.json
       --java /exact/path/to/java --perl /exact/path/to/perl
       --authority-key PRIVATE_KEY --baseline-source DIR --candidate-source DIR
       --perl5-source DIR
       [--requirements phase36_acceptance_requirements.json]

Validate raw, hash-bound Phase 36 performance artifacts and publish final
evidence by exclusive same-directory atomic link. Exit 0 means passed, 1
failed, and 2 is a
sealed committed-heap/RSS review stop that can never auto-pass.
USAGE
    exit $status;
}
