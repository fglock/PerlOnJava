#!/usr/bin/env perl

use strict;
use warnings;

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
my ($input, $output, $java, $help);
GetOptions(
    'requirements=s' => \$requirements,
    'input=s' => \$input,
    'output=s' => \$output,
    'java=s' => \$java,
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV || !defined($input) || !defined($output) || !defined($java);
my $rules = load_json($requirements, 'performance requirements', 4 * 1024 * 1024);
my $document = load_json($input, 'performance input', 16 * 1024 * 1024);
my $root = dirname(File::Spec->rel2abs($input));
my $evaluation = evaluate_performance($document, $rules, $root, {
    java => $java,
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

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: assemble_phase36_final_performance.pl --input DRAFT.json --output FINAL.json
       --java /exact/path/to/java
       [--requirements phase36_acceptance_requirements.json]

Validate raw, hash-bound Phase 36 performance artifacts and publish final
evidence by exclusive same-directory atomic link. Exit 0 means passed, 1
failed, and 2 is a
sealed committed-heap/RSS review stop that can never auto-pass.
USAGE
    exit $status;
}
