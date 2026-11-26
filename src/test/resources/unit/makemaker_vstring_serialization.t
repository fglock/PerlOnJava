use strict;
use warnings;
use Test::More;
use Cwd qw(getcwd);
use File::Temp qw(tempdir);
use ExtUtils::MakeMaker ();

plan skip_all => 'bundled MakeMaker is not loaded' unless $ExtUtils::MakeMaker::VERSION eq '7.78';

my $original_dir = getcwd();
my $temporary_dir = tempdir(CLEANUP => 1);
END { chdir $original_dir if defined $original_dir }
chdir $temporary_dir or die "chdir $temporary_dir: $!";
open my $makefile_pl, '>', 'Makefile.PL' or die "create Makefile.PL: $!";
close $makefile_pl or die "close Makefile.PL: $!";

ExtUtils::MakeMaker::WriteMakefile(
    NAME             => 'Local::VstringVersion',
    VERSION          => v0.1.0,
    MIN_PERL_VERSION => v5.10.1,
    PREREQ_PM        => { 'Local::RuntimePrerequisite' => 0.7.10 },
    BUILD_REQUIRES   => { 'Local::BuildPrerequisite' => 1.2.3 },
    TEST_REQUIRES    => { 'Local::TestPrerequisite' => 2.3.4 },
    CONFIGURE_REQUIRES => { 'Local::ConfigurePrerequisite' => 3.4.5 },
);

open my $makefile_fh, '<', 'Makefile' or die "open Makefile: $!";
my $makefile = do { local $/; <$makefile_fh> };
close $makefile_fh or die "close Makefile: $!";
like($makefile, qr/^VERSION = v0\.1\.0$/m,
     'VERSION v-string is serialized as printable version text');
unlike($makefile, qr/\bvv0\.1\.0\b/,
       'generated build messages do not duplicate the v-string prefix');
like($makefile, qr/^#     MIN_PERL_VERSION => q\[[^\x00-\x1f\x7f]+\]$/m,
     'minimum Perl v-string is printable in generated comments');
unlike($makefile, qr/[\x00-\x08\x0b-\x1f\x7f]/,
       'generated Makefile contains no control bytes');
like($makefile, qr/Local::RuntimePrerequisite=>q\[v0\.7\.10\]/,
     'runtime prerequisite v-string is printable in Makefile comments');
like($makefile, qr/Local::BuildPrerequisite=>q\[v1\.2\.3\]/,
     'build prerequisite v-string is printable in Makefile comments');
like($makefile, qr/Local::TestPrerequisite=>q\[v2\.3\.4\]/,
     'test prerequisite v-string is printable in Makefile comments');
like($makefile, qr/Local::ConfigurePrerequisite=>q\[v3\.4\.5\]/,
     'configure prerequisite v-string is printable in Makefile comments');
require Parse::CPAN::Meta;
my $mymeta = Parse::CPAN::Meta->load_file('MYMETA.yml');
is($mymeta->{requires}{'Local::RuntimePrerequisite'}, 'v0.7.10',
   'MYMETA runtime prerequisite is parseable and printable');
is($mymeta->{build_requires}{'Local::BuildPrerequisite'}, 'v1.2.3',
   'MYMETA build prerequisite is parseable and printable');
is($mymeta->{build_requires}{'Local::TestPrerequisite'}, 'v2.3.4',
   'MYMETA test prerequisite is parseable and printable');
is($mymeta->{configure_requires}{'Local::ConfigurePrerequisite'}, 'v3.4.5',
   'MYMETA configure prerequisite is parseable and printable');
is(ExtUtils::MakeMaker::_version_compare('0.7.9', 'v0.7.10'), -1,
   'prerequisite comparison preserves v-string version semantics');
is(system(($ENV{MAKE} || 'make'), '-f', 'Makefile', '-n', 'all'), 0,
   'generated Makefile parses and expands its all target');
done_testing();
