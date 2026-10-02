use strict;
use warnings;

use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use Test::More;

our ($perl_oracle_timeout, $perl_oracle_log_dir);

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools', 'cpan_random_tester.pl');
open my $fh, '<', $tool or die "cannot read $tool: $!";
my $source = do { local $/; <$fh> };
close $fh or die "cannot close $tool: $!";

my ($timeout_source) = $source =~ /(sub effective_oracle_timeout_limits \{.*?)(?=\n\n# ─)/s;
ok(defined $timeout_source, 'extracted standard-Perl oracle timeout calculation');
eval $timeout_source;
die "cannot load oracle timeout calculation: $@" if $@;

my ($probe_source) = $source =~ /(sub standard_perl_module_probe_command \{.*?)(?=\n\n# ═)/s;
ok(defined $probe_source, 'extracted standard-Perl module probe');
eval $probe_source;
die "cannot load standard-Perl module probe: $@" if $@;

my ($apply_source) = $source =~ /(sub apply_standard_perl_oracle \{.*?)(?=\nsub cpan_archive_for_module_in_log)/s;
ok(defined $apply_source, 'extracted standard-Perl oracle result handling');
eval $apply_source;
die "cannot load standard-Perl oracle result handling: $@" if $@;

my $cmd = standard_perl_module_probe_command('strict');
is_deeply(
    $cmd,
    [$^X, '-Mstrict', '-e', 'print "PERLONJAVA_MODULE_LOAD_OK\n"'],
    'module probe invokes the selected module through the current Perl executable',
);
ok(!standard_perl_module_probe_command('bad module'),
    'module probe rejects invalid module names');
ok(standard_perl_module_probe_succeeded("PERLONJAVA_MODULE_LOAD_OK\n"),
    'module probe recognizes a successful load marker');
ok(!standard_perl_module_probe_succeeded("Can't locate Missing.pm\n"),
    'module probe recognizes a standard-Perl load failure');

$perl_oracle_timeout = 30;
$perl_oracle_log_dir = tempdir(CLEANUP => 1);
sub safe_log_name { return $_[0] }

my ($mock_output, $mock_timeout);
{
    no warnings 'redefine';
    no warnings 'once';
    local *main::run_with_timeout = sub {
        return ($mock_output, $mock_timeout, '');
    };

    ($mock_output, $mock_timeout) = ("Can't locate Authen/SASL/Perl/Layer.pm\n", 0);
    is((run_standard_perl_module_probe('Authen::SASL::Perl::Layer', 90))[0], 'FAIL',
        'an unresolved module is a standard-Perl failure even without a CPAN archive');

    ($mock_output, $mock_timeout) = ("PERLONJAVA_MODULE_LOAD_OK\n", 0);
    is((run_standard_perl_module_probe('strict', 90))[0], 'PASS',
        'a module available to standard Perl passes the module probe');

    ($mock_output, $mock_timeout) = ('', 1);
    is((run_standard_perl_module_probe('strict', 90))[0], 'TIMEOUT',
        'a timed-out module probe remains inconclusive');

    local *main::cpan_archive_for_module_in_log = sub { return undef };
    local *main::run_standard_perl_module_probe = sub { return ('FAIL', 'probe.log') };
    my $source_log = File::Spec->catfile($perl_oracle_log_dir, 'target.log');
    open my $source_fh, '>', $source_log or die "cannot create $source_log: $!";
    close $source_fh or die "cannot close $source_log: $!";
    my $unresolved_target = [{ module => 'Authen::SASL::Perl::Layer', status => 'FAIL' }];
    apply_standard_perl_oracle($unresolved_target, $source_log, 90);
    is($unresolved_target->[0]{status}, 'PERL_FAIL',
        'a missing CPAN archive falls back to standard-Perl module loading');
    is($unresolved_target->[0]{perl_oracle_log}, 'probe.log',
        'fallback probe log is attached to the result');
}

done_testing;
