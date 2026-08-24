use strict;
use warnings;
use utf8;
use Test::More;
use Digest::SHA qw(sha256_hex);
use File::Copy qw(copy);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IPC::Open3;
use Symbol qw(gensym);
use Unicode::UCD ();

my $repo = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
use lib File::Spec->catdir($FindBin::Bin, '..', 'lib');
use PerlOnJava::UnicodeGenerator qw(verify_unicode_notice);

ok("\x{212a}" =~ /\Ak\z/iu, 'Kelvin sign belongs to the simple k fold closure');
ok("\x{17f}" =~ /\As\z/iu, 'long s belongs to the simple s fold closure');
ok('ss' =~ /\A\x{df}\z/iu, 'sharp s folds forward to two code points');
ok("\x{df}" =~ /\As(?:s)\z/iu,
    'reverse sharp-s fold crosses separate regex nodes');
ok("\x{1e9e}" =~ /\Ass\z/iu,
    'capital sharp s shares the reverse full fold');
ok("j\x{30c}" =~ /\A\x{1f0}\z/iu,
    'two-code-point combining fold is available in reverse');
ok("\x{3b9}\x{308}\x{301}" =~ /\A\x{390}\z/iu,
    'three-code-point combining fold is available in reverse');
ok('ffi' =~ /\A\x{fb03}\z/iu, 'ligature folds across three literal nodes');

ok("\x{131}" !~ /\AI\z/iu,
    'default folding excludes the Turkic dotless-I mapping');
ok('i' !~ /\A\x{130}\z/iu,
    'default folding does not apply the Turkic dotted-I shortcut');
ok("i\x{307}" =~ /\A\x{130}\z/iu,
    'default dotted-I full fold retains the combining dot');
ok("\x{df}" !~ /\As(?:s)\z/iaa,
    'ASCII strict policy rejects ordinary ASCII sharp-s crossing');
ok("\x{df}" =~ /\A\x{17f}\x{17f}\z/iaa,
    'Perl ASCII strict policy retains the long-s sharp-s exception');

my @version = split /\./, Unicode::UCD::UnicodeVersion();
SKIP: {
    skip 'Unicode 17 simple-fold anchors require Perl built with Unicode 17', 4
        if $version[0] < 17;
    ok("\x{a7ce}" =~ /\A\x{a7cf}\z/iu, 'Unicode 17 A7CE simple fold');
    ok("\x{a7d2}" =~ /\A\x{a7d3}\z/iu, 'Unicode 17 A7D2 simple fold');
    ok("\x{a7d4}" =~ /\A\x{a7d5}\z/iu, 'Unicode 17 A7D4 simple fold');
    ok("\x{16ea0}" =~ /\A\x{16ebb}\z/iu, 'Unicode 17 Beria Erfe simple fold');
}

my $notice_fixture = "# © 2025 Unicode®, Inc.\n# deliberately incomplete notice\n";
utf8::encode($notice_fixture);
my $notice_failure = eval {
    verify_unicode_notice('synthetic-CaseFolding.txt', $notice_fixture);
    '';
};
like($@, qr/does not preserve the Unicode trademark notice/,
    'shared source validation rejects a missing Unicode notice');

my $perl_root = $ENV{PERLONJAVA_PERL_ROOT}
    // File::Spec->catdir($repo, 'perl5');
my $unicode_root = $ENV{PERLONJAVA_UNICODE_ROOT}
    // File::Spec->catdir($perl_root, 'lib', 'unicore');
my $generator = File::Spec->catfile(
    $repo, 'dev', 'tools', 'generate_perl_unicode_case_fold_data.pl');
my $checked_in = File::Spec->catfile(
    $repo, 'third_party', 'joni', 'src', 'org', 'joni', 'PerlUnicodeCaseFoldData.java');

SKIP: {
    my @unicode_sources = qw(version CaseFolding.txt SpecialCasing.txt);
    my @perl_sources = (
        'regen/regcharclass_multi_char_folds.pl',
        'regen/mk_invlists.pl',
        'lib/unicore/mktables',
        'charclass_invlists.inc',
        'regcharclass.h',
        'regen/regcharclass.pl',
    );
    skip 'selected current Perl source checkout is unavailable', 8
        unless -f File::Spec->catfile($unicode_root, 'CaseFolding.txt')
            && -f File::Spec->catfile($perl_root, 'regcharclass.h');

    my ($first_status, $first_output) = run_generator(
        $generator, $unicode_root, $perl_root);
    is($first_status, 0, 'case-fold generator succeeds on recorded current sources');
    is(sha256_hex($first_output),
        '4bab60ee50b08d24b9b69863688c2862c724fb58ba76a749974af074b19c3b82',
        'case-fold generator emits the recorded output hash');
    is($first_output, read_raw($checked_in),
        'case-fold generator reproduces the checked-in Java bytes');

    my ($second_status, $second_output) = run_generator(
        $generator, $unicode_root, $perl_root);
    is($second_status, 0, 'second case-fold generation succeeds');
    is($second_output, $first_output,
        'two consecutive case-fold generations are deterministic');

    my $temporary = tempdir(CLEANUP => 1);
    my $temporary_perl = File::Spec->catdir($temporary, 'perl5');
    my $temporary_unicode = File::Spec->catdir($temporary_perl, 'lib', 'unicore');
    for my $relative (@unicode_sources) {
        copy_source($unicode_root, $temporary_unicode, $relative);
    }
    for my $relative (@perl_sources) {
        copy_source($perl_root, $temporary_perl, $relative);
    }
    open my $corrupt, '>>:raw', File::Spec->catfile($temporary_unicode, 'CaseFolding.txt')
        or die "Cannot corrupt CaseFolding fixture: $!";
    print {$corrupt} "# corruption\n";
    close $corrupt or die "Cannot close corrupted fixture: $!";

    my ($corrupt_status, $corrupt_output) = run_generator(
        $generator, $temporary_unicode, $temporary_perl);
    isnt($corrupt_status, 0, 'case-fold generator rejects source checksum drift');
    like($corrupt_output, qr/CaseFolding\.txt SHA-256 mismatch/,
        'checksum failure identifies CaseFolding source');
    is(read_raw($checked_in), $first_output,
        'failed source validation leaves checked-in output untouched');
}

done_testing;

sub run_generator {
    my ($script, $source_root, $source_perl_root) = @_;
    local %ENV = %ENV;
    $ENV{PERLONJAVA_UNICODE_ROOT} = $source_root;
    $ENV{PERLONJAVA_PERL_ROOT} = $source_perl_root;
    my $error = gensym;
    my $pid = open3(undef, my $stdout, $error, $^X, $script);
    binmode $stdout, ':raw';
    binmode $error, ':raw';
    local $/;
    my $output = (<$stdout> // '') . (<$error> // '');
    waitpid($pid, 0);
    return ($? >> 8, $output);
}

sub copy_source {
    my ($from_root, $to_root, $relative) = @_;
    my @parts = split m{/}, $relative;
    my $source = File::Spec->catfile($from_root, @parts);
    my $target = File::Spec->catfile($to_root, @parts);
    my (undef, $directory) = File::Spec->splitpath($target);
    make_path($directory);
    copy($source, $target) or die "Cannot copy $source to $target: $!";
}

sub read_raw {
    my ($path) = @_;
    open my $input, '<:raw', $path or die "Cannot read $path: $!";
    local $/;
    my $bytes = <$input>;
    close $input or die "Cannot close $path: $!";
    return $bytes;
}
