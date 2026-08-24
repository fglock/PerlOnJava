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

done_testing;
