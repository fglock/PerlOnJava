use strict;
use warnings;
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IPC::Open3;
use Symbol qw(gensym);
use Test::More;

my $repo = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..', '..'));
my $generator = File::Spec->catfile(
    $repo, 'dev', 'regex', 'tools', 'generate_perl_unicode_indic_category_data.pl');
my $root = tempdir(CLEANUP => 1);
make_path($root);

write_sources('18.0.0');
my ($status, $first) = run_generator();
is($status, 0, 'generator accepts a valid newer current Unicode source tree');
like($first, qr/UNICODE_VERSION = "18\.0\.0"/,
    'generated provenance derives the newer Unicode version');
my ($second_status, $second) = run_generator();
is($second_status, 0, 'second newer-source generation succeeds');
is($second, $first, 'newer current-source generation is deterministic');

my $syllabic = File::Spec->catfile($root, 'IndicSyllabicCategory.txt');
my $valid = read_file($syllabic);
(my $missing_notice = $valid) =~ s/^# © 2025 Unicode®, Inc\.\n//m;
write_file($syllabic, $missing_notice);
my ($notice_status, $notice_log) = run_generator();
isnt($notice_status, 0, 'generator rejects a current source missing its notice');
like($notice_log, qr/does not preserve the Unicode copyright notice/,
    'missing notice has a focused diagnostic');

write_file($syllabic, $valid =~ s/; Other/; Unknown/r);
my ($malformed_status, $malformed_log) = run_generator();
isnt($malformed_status, 0, 'generator rejects malformed current property data');
like($malformed_log, qr/(?:unknown explicit value 'Unknown'|expected default Other, found Unknown)/,
    'malformed property value is named');

done_testing;

sub notice {
    return "# © 2025 Unicode®, Inc.\n"
        . "# Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the U.S. and other countries.\n"
        . "# For terms of use and license, see https://www.unicode.org/terms_of_use.html\n";
}

sub write_sources {
    my ($version) = @_;
    write_file(File::Spec->catfile($root, 'version'), "$version\n");
    write_file(File::Spec->catfile($root, 'IndicSyllabicCategory.txt'),
        "# IndicSyllabicCategory-$version.txt\n" . notice()
        . "# \@missing: 0000..10FFFF; Other\n0041 ; Other\n");
    write_file(File::Spec->catfile($root, 'IndicPositionalCategory.txt'),
        "# IndicPositionalCategory-$version.txt\n" . notice()
        . "# \@missing: 0000..10FFFF; Not_Applicable\n0042 ; Not_Applicable\n");
    write_file(File::Spec->catfile($root, 'PropValueAliases.txt'),
        "# PropertyValueAliases-$version.txt\n" . notice()
        . "InSC ; Other ; Other\nInPC ; NA ; Not_Applicable\n");
    write_file(File::Spec->catfile($root, 'PropertyAliases.txt'),
        "# PropertyAliases-$version.txt\n" . notice()
        . "InSC ; Indic_Syllabic_Category\nInPC ; Indic_Positional_Category\n");
}

sub run_generator {
    local %ENV = %ENV;
    $ENV{PERLONJAVA_UNICODE_ROOT} = $root;
    my $error = gensym;
    my $pid = open3(undef, my $stdout, $error, $^X, $generator);
    local $/;
    my $bytes = (<$stdout> // '') . (<$error> // '');
    waitpid($pid, 0);
    return ($? >> 8, $bytes);
}

sub write_file {
    my ($path, $bytes) = @_;
    open my $output, '>:raw', $path or die "Cannot write $path: $!";
    print {$output} $bytes or die "Cannot write $path: $!";
    close $output or die "Cannot close $path: $!";
}

sub read_file {
    my ($path) = @_;
    open my $input, '<:raw', $path or die "Cannot read $path: $!";
    local $/;
    my $bytes = <$input>;
    close $input or die "Cannot close $path: $!";
    return $bytes;
}
