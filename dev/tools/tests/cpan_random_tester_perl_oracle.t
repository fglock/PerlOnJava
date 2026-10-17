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

my ($cpan_helpers_source) = $source =~ /(sub standard_perl_cpan_command \{.*?)(?=\n\n# ═)/s;
ok(defined $cpan_helpers_source, 'extracted standard-Perl CPAN helpers');
eval $cpan_helpers_source;
die "cannot load standard-Perl CPAN helpers: $@" if $@;

my ($apply_source) = $source =~ /(sub apply_standard_perl_oracle \{.*?)(?=\nsub run_standard_perl_oracle)/s;
ok(defined $apply_source, 'extracted standard-Perl oracle result handling');
eval $apply_source;
die "cannot load standard-Perl oracle result handling: $@" if $@;

my $cmd = standard_perl_cpan_command(
    '/usr/bin/cpan', '/tmp/oracle-cpan-config.pm', 'XML::Parser::Style::RDF',
);
is_deeply(
    $cmd,
    ['/usr/bin/cpan', '-j', '/tmp/oracle-cpan-config.pm', '-t', 'XML::Parser::Style::RDF'],
    'oracle asks standard CPAN to test the requested module with its isolated config',
);
ok(!standard_perl_cpan_command('/usr/bin/cpan', '/tmp/cpan-config.pm', 'bad module'),
    'CPAN command builder rejects invalid module names');
ok(!standard_perl_cpan_command('', '/tmp/cpan-config.pm', 'Foo::Bar'),
    'CPAN command builder rejects an unavailable cpan executable');
ok(!standard_perl_cpan_command('/usr/bin/cpan', '', 'Foo::Bar'),
    'CPAN command builder rejects a missing isolated config');

$perl_oracle_timeout = 30;
$perl_oracle_log_dir = tempdir(CLEANUP => 1);
sub safe_log_name { return $_[0] }

my $isolated_cpan_home = File::Spec->catdir($perl_oracle_log_dir, 'isolated-cpan');
my $isolated_config = File::Spec->catfile($perl_oracle_log_dir, 'isolated-cpan-config.pm');
ok(write_standard_perl_cpan_config($isolated_config, $isolated_cpan_home),
    'writes the standard Perl CPAN configuration into the oracle directory');
my $saved_cpan_config = $CPAN::Config;
ok(do $isolated_config, 'generated CPAN config is loadable');
is($CPAN::Config->{cpan_home}, $isolated_cpan_home,
    'isolated CPAN config redirects CPAN state into the oracle directory');
is($CPAN::Config->{build_dir}, File::Spec->catdir($isolated_cpan_home, 'build'),
    'isolated CPAN config redirects build trees into the oracle directory');
$CPAN::Config = $saved_cpan_config;

{
    no warnings 'redefine';
    no warnings 'once';
    local *main::run_standard_perl_oracle = sub { return ('FAIL', 'oracle.log') };
    my $unresolved_target = [{ module => 'Authen::SASL::Perl::Layer', status => 'FAIL' }];
    apply_standard_perl_oracle($unresolved_target, 90);
    is($unresolved_target->[0]{status}, 'PERL_FAIL',
        'a standard CPAN test failure is marked as a standard-Perl failure');
    is($unresolved_target->[0]{perl_oracle_log}, 'oracle.log',
        'standard CPAN oracle log is attached to the result');

    local *main::run_standard_perl_oracle = sub { return ('UNAVAILABLE', 'oracle.log') };
    my $download_failure = [{ module => 'XML::Parser::Style::RDF', status => 'FAIL' }];
    apply_standard_perl_oracle($download_failure, 90);
    is($download_failure->[0]{status}, 'FAIL',
        'an unavailable standard CPAN test does not claim the module fails on Perl');
}

done_testing;
