use strict;
use warnings;

use File::Spec;
use File::Temp qw(tempfile);
use FindBin;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools', 'cpan_random_tester.pl');

open my $fh, '<', $tool or die "cannot read $tool: $!";
my $source = do { local $/; <$fh> };
close $fh or die "cannot close $tool: $!";

my ($parser_source) = $source =~ /(sub parse_all_module_results \{.*?)(?=\n# Helpers)/s;
ok(defined $parser_source, 'extracted the CPAN result parser');
eval $parser_source;
die "cannot load CPAN result parser: $@" if $@;

my ($limit_source) = $source =~ /(sub effective_timeout_limits \{.*?)(?=\n# ─)/s;
ok(defined $limit_source, 'extracted timeout-limit calculation');
eval $limit_source;
die "cannot load timeout-limit calculation: $@" if $@;

my ($timeout_source) = $source =~ /(sub record_target_timeout \{.*?)(?=\n\n# ═)/s;
ok(defined $timeout_source, 'extracted timeout-result reconciliation');
eval $timeout_source;
die "cannot load timeout-result reconciliation: $@" if $@;

my $output = <<'LOG';
Running test for module 'POE::Loop::Gtk'
Checksum for /tmp/cpan/sources/authors/id/R/RC/RCAPUTO/POE-Loop-Gtk-1.306.tar.gz ok
---- Unsatisfied dependencies detected during ----
    POE::Test::Loops [configure_requires]
Running test for module 'POE::Test::Loops'
Checksum for /tmp/cpan/sources/authors/id/R/RC/RCAPUTO/POE-Test-Loops-1.360.tar.gz ok
Configuring R/RC/RCAPUTO/POE-Test-Loops-1.360.tar.gz with Makefile.PL
Running make for R/RC/RCAPUTO/POE-Test-Loops-1.360.tar.gz
  Skipping dependency tests; use --strict-dependency-tests to enable them
Configuring R/RC/RCAPUTO/POE-Loop-Gtk-1.306.tar.gz with Makefile.PL
Running make test for R/RC/RCAPUTO/POE-Loop-Gtk-1.306.tar.gz
Result: FAIL
make test -- NOT OK
LOG

my @results = parse_all_module_results($output);
is_deeply(
    [map { $_->{module} } @results],
    ['POE::Loop::Gtk'],
    'a target test block remains associated with its target after dependency discovery',
);
is($results[0]{status}, 'FAIL', 'target test failure is retained');

my ($log_fh, $log_path) = tempfile();
print {$log_fh} $output;
close $log_fh or die "cannot close $log_path: $!";
my @streamed_results = parse_all_module_results_from_file($log_path);
is_deeply(
    [map { $_->{module} } @streamed_results],
    ['POE::Loop::Gtk'],
    'streaming parser preserves the target association too',
);

my $tgz_success = <<'LOG';
Running test for module 'Statistics::Burst'
Checksum for /tmp/cpan/sources/authors/id/T/TO/TOMMIE/Statistics-Burst-0.2.tgz ok
Configuring T/TO/TOMMIE/Statistics-Burst-0.2.tgz with Makefile.PL
Running make test for TOMMIE/Statistics-Burst-0.2.tgz
t/Statistics-Burst.t .. ok
All tests successful.
Files=1, Tests=3
Result: PASS
  /usr/bin/make test -- OK
LOG

my @tgz_memory_results = parse_all_module_results($tgz_success);
is($tgz_memory_results[0]{status}, 'PASS',
    'in-memory parser recognizes a successful CPAN .tgz test');

my ($tgz_log_fh, $tgz_log_path) = tempfile();
print {$tgz_log_fh} $tgz_success;
close $tgz_log_fh or die "cannot close $tgz_log_path: $!";
my @tgz_results = parse_all_module_results_from_file($tgz_log_path);
is_deeply(
    [map { $_->{module} } @tgz_results],
    ['Statistics::Burst'],
    'streaming parser recognizes CPAN .tgz test archives',
);
is($tgz_results[0]{status}, 'PASS',
    'successful CPAN .tgz test is recorded as a pass');
is($tgz_results[0]{tests}, 3,
    'successful CPAN .tgz test retains its test count');

my $build_failure_after_dependency = <<'LOG';
Running test for module 'Marpa::R2'
Checksum for /tmp/cpan/sources/authors/id/J/JK/JKEGL/Marpa-R2-14.000000.tar.gz ok
---- Unsatisfied dependencies detected during ----
    PPI [configure_requires]
Running test for module 'PPI'
Checksum for /tmp/cpan/sources/authors/id/M/MI/MITHALDU/PPI-1.291.tar.gz ok
Configuring M/MI/MITHALDU/PPI-1.291.tar.gz with Makefile.PL
Running make for M/MI/MITHALDU/PPI-1.291.tar.gz
  /usr/bin/make -- OK
Configuring J/JK/JKEGL/Marpa-R2-14.000000.tar.gz with Build.PL
Running Build for J/JK/JKEGL/Marpa-R2-14.000000.tar.gz
  /tmp/jperl Build -- NOT OK
LOG

my @build_results = parse_all_module_results($build_failure_after_dependency);
is_deeply(
    [map { $_->{module} } @build_results],
    ['Marpa::R2'],
    'parent build failure is not attributed to the completed dependency',
);
is($build_results[0]{error}, 'Build failed', 'parent build failure is retained');

my ($build_log_fh, $build_log_path) = tempfile();
print {$build_log_fh} $build_failure_after_dependency;
close $build_log_fh or die "cannot close $build_log_path: $!";
my @streamed_build_results = parse_all_module_results_from_file($build_log_path);
is_deeply(
    [map { $_->{module} } @streamed_build_results],
    ['Marpa::R2'],
    'streaming parser attributes resumed-parent build failure correctly',
);

my $retry_after_missing_prerequisite = <<'LOG';
Running test for module 'Emoji::NationalFlag'
Checksum for /tmp/cpan/sources/authors/id/P/PU/PUNYTAN/Emoji-NationalFlag-0.01.tar.gz ok
Running Build test for PUNYTAN/Emoji-NationalFlag-0.01.tar.gz
Can't locate Locale/Country.pm in @INC
Result: FAIL
---- Unsatisfied dependencies detected during ----
    Locale::Country [test_requires]
Running test for module 'Locale::Country'
Checksum for /tmp/cpan/sources/authors/id/S/SB/SBECK/Locale-Codes-3.90.tar.gz ok
Running Build test for PUNYTAN/Emoji-NationalFlag-0.01.tar.gz
t/basic.t .. ok
All tests successful.
Files=2, Tests=3
Result: PASS
Build test -- OK
LOG

my @retry_results = parse_all_module_results($retry_after_missing_prerequisite);
is_deeply(
    [map { $_->{module} } @retry_results],
    ['Emoji::NationalFlag'],
    'retry replaces the earlier module result',
);
is($retry_results[0]{status}, 'PASS', 'successful retry replaces the stale failure');
is($retry_results[0]{tests}, 3, 'successful retry retains its test count');

my ($retry_log_fh, $retry_log_path) = tempfile();
print {$retry_log_fh} $retry_after_missing_prerequisite;
close $retry_log_fh or die "cannot close $retry_log_path: $!";
my @streamed_retry_results = parse_all_module_results_from_file($retry_log_path);
is_deeply(
    [map { $_->{module} } @streamed_retry_results],
    ['Emoji::NationalFlag'],
    'streaming parser keeps only the retried module result',
);
is($streamed_retry_results[0]{status}, 'PASS',
    'streaming parser uses the successful retry result');

my %slow = ('Image::ExifTool' => 3600);
is_deeply(
    [effective_timeout_limits('Image::ExifTool', 120, 600, 300, \%slow)],
    [3600, 4200],
    'known-slow target keeps its complete soft limit and idle grace',
);
is_deeply(
    [effective_timeout_limits('Ordinary::Module', 120, 600, 300, \%slow)],
    [120, 300],
    'ordinary target retains the requested global hard cap',
);
is_deeply(
    [effective_timeout_limits('Image::ExifTool', 120, 600, 0, \%slow)],
    [3600, 0],
    'disabled hard cap remains disabled for known-slow targets',
);

my @partial = ({
    module => 'Image::ExifTool', status => 'FAIL',
    tests => undef, pass_count => undef,
    error => 'Unknown test outcome',
});
record_target_timeout(\@partial, 'Image::ExifTool',
    'TIMEOUT (runtime >4200s; last output 1s ago)');
is($partial[0]{error}, 'TIMEOUT (runtime >4200s; last output 1s ago)',
    'timeout replaces an unknown result from a partial target block');

my @definitive = ({
    module => 'Example::Failure', status => 'FAIL',
    tests => 3, pass_count => 2,
    error => '1/3 subtests failed',
});
record_target_timeout(\@definitive, 'Example::Failure',
    'TIMEOUT (runtime >300s; last output 1s ago)');
is($definitive[0]{error}, '1/3 subtests failed',
    'timeout does not hide a definitive harness failure');

my @dependency_only = ({
    module => 'Dependency', status => 'PASS', error => '',
});
record_target_timeout(\@dependency_only, 'Missing::Target',
    'TIMEOUT (runtime >300s; last output 1s ago)');
is_deeply(
    $dependency_only[-1],
    {
        module => 'Missing::Target', status => 'FAIL',
        tests => undef, pass_count => undef,
        error => 'TIMEOUT (runtime >300s; last output 1s ago)',
    },
    'timeout adds a target result when only dependencies were parsed',
);

done_testing;
