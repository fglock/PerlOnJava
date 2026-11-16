use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin;
use IPC::Open3;
use JSON::PP qw(decode_json);
use Symbol qw(gensym);
use Test::More;

my $repo = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..', '..'));
my $perl_root = File::Spec->catdir($repo, 'perl5');
my $patchlevel = File::Spec->catfile($perl_root, 'patchlevel.h');
my $manifest_path = File::Spec->catfile(
    $repo, 'dev', 'regex', 'tools', 'perl_unicode_data_generators.json');

plan skip_all => 'requires the current perl5/ blead checkout'
    unless -f $patchlevel;

my $manifest = decode_json(read_file($manifest_path));
is($manifest->{perl_source_policy}, 'current-checkout',
    'Unicode generation follows the current checkout policy');

my ($revision) = read_file($patchlevel) =~ /^#define\s+PERL_REVISION\s+(\d+)/m;
my ($version) = read_file($patchlevel) =~ /^#define\s+PERL_VERSION\s+(\d+)/m;
my ($subversion) = read_file($patchlevel) =~ /^#define\s+PERL_SUBVERSION\s+(\d+)/m;
ok(defined $revision && defined $version && defined $subversion,
    'blead patchlevel exposes its language version');
is($manifest->{perl_version}, join('.', $revision, $version, $subversion),
    'manifest records the blead language version');

my ($git_status, $blead_commit) = run_command(
    'git', '-C', $perl_root, 'rev-parse', 'HEAD');
is($git_status, 0, 'reads the perl5/ blead commit');
chomp $blead_commit;
is($manifest->{perl_commit}, $blead_commit,
    'manifest records the exact perl5/ blead commit');

my $case_fold_data = File::Spec->catfile(
    $repo, 'third_party', 'joni', 'src', 'org', 'joni', 'PerlUnicodeCaseFoldData.java');
my $case_fold_java = read_file($case_fold_data);
my %case_fold_perl_sources = (
    PERL_CURRENT_MULTI_FOLD_GENERATOR_SHA256 => 'regen/regcharclass_multi_char_folds.pl',
    PERL_CURRENT_INVERSION_GENERATOR_SHA256 => 'regen/mk_invlists.pl',
    PERL_CURRENT_MKTABLES_SHA256 => 'lib/unicore/mktables',
    PERL_CURRENT_INVERSION_DATA_SHA256 => 'charclass_invlists.inc',
    PERL_CURRENT_REGCHARCLASS_SHA256 => 'regcharclass.h',
    PERL_CURRENT_REGCHARCLASS_GENERATOR_SHA256 => 'regen/regcharclass.pl',
);
for my $constant (sort keys %case_fold_perl_sources) {
    my $relative = $case_fold_perl_sources{$constant};
    my $source = File::Spec->catfile($perl_root, split m{/}, $relative);
    my ($recorded) = $case_fold_java =~ /^\s*static final String \Q$constant\E = "([0-9a-f]{64})";$/m;
    is($recorded, sha256_hex(read_file($source)),
        "$constant records the current blead source");
}

local $ENV{PERLONJAVA_PERL_ROOT} = $perl_root;
my $generator = File::Spec->catfile(
    $repo, 'dev', 'regex', 'tools', 'generate_perl_unicode_data.pl');
my ($check_status, $check_output) = run_command($^X, $generator, '--check');
is($check_status, 0,
    'all generated Unicode data matches the current blead checkout');
like($check_output, qr/^age: current \([0-9a-f]{64}\)$/m,
    'generated Age data is checked against blead');

done_testing;

sub run_command {
    my (@command) = @_;
    my $error = gensym;
    my $pid = open3(undef, my $stdout, $error, @command);
    local $/;
    my $output = (<$stdout> // '') . (<$error> // '');
    waitpid($pid, 0);
    return ($? >> 8, $output);
}

sub read_file {
    my ($path) = @_;
    open my $input, '<:raw', $path or die "Cannot read $path: $!";
    local $/;
    my $bytes = <$input>;
    close $input or die "Cannot close $path: $!";
    return $bytes;
}
