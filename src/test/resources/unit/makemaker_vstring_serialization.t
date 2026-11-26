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
is(system(($ENV{MAKE} || 'make'), '-f', 'Makefile', '-n', 'all'), 0,
   'generated Makefile parses and expands its all target');
done_testing();
