use strict;
use warnings;
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

use lib File::Spec->catdir($FindBin::Bin, '..', 'lib');
use PerlOnJava::UnicodeGenerator qw(perl_language_provenance);

my $root = tempdir('unicode provenance XXXX', TMPDIR => 1, CLEANUP => 1);
my $unicode_version = '17.0.0';
my $unicode_root = File::Spec->catdir(
    $root, 'dev', 'unicode', $unicode_version);
my $tools_root = File::Spec->catdir($root, 'dev', 'tools');
make_path($unicode_root, $tools_root);
write_manifest($root, '5.45.3');

local $ENV{PERLONJAVA_PERL_ROOT};
is(perl_language_provenance(
        repo_root => $root,
        unicode_root => $unicode_root,
        unicode_version => $unicode_version,
    ), '5.45.3',
    'checked-in source uses refreshed manifest provenance without perl5');

my $perl_root = File::Spec->catdir($root, 'perl5');
make_path($perl_root);
write_file(File::Spec->catfile($perl_root, 'patchlevel.h'), <<'PATCHLEVEL');
#define PERL_REVISION 5
#define PERL_VERSION 47
#define PERL_SUBVERSION 1
PATCHLEVEL
is(perl_language_provenance(
        repo_root => $root,
        unicode_root => $unicode_root,
        unicode_version => $unicode_version,
    ), '5.47.1',
    'a current development checkout takes precedence over recorded provenance');

my $explicit_repo = tempdir('explicit provenance XXXX', TMPDIR => 1, CLEANUP => 1);
my $explicit_unicode = File::Spec->catdir(
    $explicit_repo, 'dev', 'unicode', $unicode_version);
make_path($explicit_unicode, File::Spec->catdir($explicit_repo, 'dev', 'tools'));
write_manifest($explicit_repo, '5.45.3');
my $explicit_root = File::Spec->catdir($explicit_repo, 'missing explicit perl');
local $ENV{PERLONJAVA_PERL_ROOT} = $explicit_root;
eval {
    perl_language_provenance(
        repo_root => $explicit_repo,
        unicode_root => $explicit_unicode,
        unicode_version => $unicode_version,
    );
};
like($@, qr/^No complete current Perl source tree: .*missing explicit perl.*patchlevel\.h/s,
    'an incomplete explicit checkout remains a hard error');

local $ENV{PERLONJAVA_PERL_ROOT};
my $external_unicode = File::Spec->catdir($root, 'external unicode');
make_path($external_unicode);
eval {
    perl_language_provenance(
        repo_root => File::Spec->catdir($root, 'no perl repository'),
        unicode_root => $external_unicode,
        unicode_version => $unicode_version,
    );
};
like($@, qr/^No complete current Perl source tree:/,
    'an external Unicode source cannot borrow checked-in provenance');

done_testing;

sub write_manifest {
    my ($repo_root, $perl_version) = @_;
    my $path = File::Spec->catfile(
        $repo_root, 'dev', 'tools', 'perl_unicode_data_generators.json');
    write_file($path, JSON::PP->new->canonical->encode({
        schema_version => 2,
        perl_source_policy => 'current-checkout',
        perl_version => $perl_version,
        unicode_version => $unicode_version,
    }));
}

sub write_file {
    my ($path, $bytes) = @_;
    open my $output, '>:raw', $path or die "Cannot write $path: $!";
    print {$output} $bytes or die "Cannot write $path: $!";
    close $output or die "Cannot close $path: $!";
}
