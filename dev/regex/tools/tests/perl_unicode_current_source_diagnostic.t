use strict;
use warnings;
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use Test::More;

use lib File::Spec->catdir($FindBin::Bin, '..', 'lib');
use PerlOnJava::UnicodeGenerator qw(perl_language_version select_perl_root);

my $root = tempdir(CLEANUP => 1);
my $explicit = File::Spec->catdir($root, 'explicit-perl');
local $ENV{PERLONJAVA_PERL_ROOT} = $explicit;

eval {
    select_perl_root(
        repo_root => $root,
        required => ['uni_keywords.h'],
    );
};
like($@,
    qr/^No complete current Perl source tree: \Q$explicit\E missing uni_keywords\.h/,
    'missing explicit Perl roots use current-source terminology');

my $version_root = File::Spec->catdir($root, 'version-perl');
make_path($version_root);
my $patchlevel = File::Spec->catfile($version_root, 'patchlevel.h');
open my $output, '>:raw', $patchlevel or die "Cannot write $patchlevel: $!";
print {$output} <<'PATCHLEVEL';
#define PERL_REVISION 5
#define PERL_VERSION 45
#define PERL_SUBVERSION 3
PATCHLEVEL
close $output or die "Cannot close $patchlevel: $!";

is(perl_language_version(root => $version_root), '5.45.3',
    'Perl language provenance is derived from patchlevel.h');

open $output, '>:raw', $patchlevel or die "Cannot rewrite $patchlevel: $!";
print {$output} "#define PERL_REVISION 5\n#define PERL_VERSION 45\n";
close $output or die "Cannot close $patchlevel: $!";
eval { perl_language_version(root => $version_root) };
like($@, qr/Cannot derive Perl language version from \Q$patchlevel\E/,
    'incomplete patchlevel provenance is rejected');

done_testing;
