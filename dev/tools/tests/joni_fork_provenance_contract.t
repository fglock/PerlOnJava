use strict;
use warnings;
use utf8;
use Test::More;
use Digest::SHA qw(sha256_hex);
use Encode qw(encode_utf8);
use File::Copy qw(copy);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IPC::Open3;
use JSON::PP qw(decode_json);
use Symbol qw(gensym);
use lib File::Spec->catdir($FindBin::Bin, '..', 'lib');
use PerlOnJava::UnicodeGenerator qw(verify_unicode_notice);

my $repo = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));

my $notice = read_raw(path('third_party/joni/PERLONJAVA-NOTICE.md'));
like $notice, qr/joni-2\.2\.7/, 'notice pins the upstream Joni tag';
like $notice, qr/57fd57b4f977813a7b4b35e0179943b1f06f51d7/,
    'notice pins the upstream Joni commit';
like $notice, qr/jcodings-1\.0\.64/, 'notice pins the upstream JCodings tag';
like $notice, qr/996ae0f72c5cc6bb28ef92f29bbd5ef0f63d5250/,
    'notice pins the upstream JCodings commit';
like $notice, qr/case folding.*Unicode properties.*boundaries/is,
    'notice describes maintained Perl compatibility work';
like read_raw(path('third_party/joni/README.md')),
    qr/PerlOnJava-maintained fork.*PERLONJAVA-NOTICE\.md/is,
    'fork README identifies the fork and its notice';

my $notice_fixture = tempdir(CLEANUP => 1);
my $unicode_data_fixture = File::Spec->catfile($notice_fixture, 'UnicodeData.txt');
my $readme_fixture = File::Spec->catfile($notice_fixture, 'ReadMe.txt');
write_raw($unicode_data_fixture, "0041;LATIN CAPITAL LETTER A;Lu\n");
my $distribution_notice = encode_utf8(
    "# © 2025 Unicode®, Inc.\n"
    . "# Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the U.S. and other countries.\n"
    . "# For terms of use and license, see https://www.unicode.org/terms_of_use.html\n");
write_raw($readme_fixture, $distribution_notice);
ok eval { verify_unicode_notice($unicode_data_fixture,
        read_raw($unicode_data_fixture)); 1 },
    'UnicodeData uses its pinned sibling distribution notice';
write_raw($readme_fixture, "# incomplete notice\n");
eval { verify_unicode_notice($unicode_data_fixture,
    read_raw($unicode_data_fixture)); 1 };
like $@, qr/does not preserve the Unicode copyright notice/,
    'UnicodeData rejects an incomplete sibling distribution notice';

like $notice, qr/PerlOnJava modifications include.*their tests\./s,
    'fork notice collectively attributes maintained code and its tests';
like $notice, qr/Joni remains licensed under its original MIT License\./,
    'fork notice preserves the original Joni license';

my $manifest = decode_json(read_raw(path(
    'dev/tools/perl_unicode_data_generators.json')));
my %entry = map { $_->{name} => $_ } @{$manifest->{generators}};
my @contract = (
    {
        name => 'joni-sentence-break',
        generator => 'dev/tools/generate_joni_boundary_data.pl',
        output => 'third_party/joni/src/org/joni/SentenceBreakData.java',
        sources => ['auxiliary/SentenceBreakProperty.txt'],
    },
    {
        name => 'joni-word-break',
        generator => 'dev/tools/generate_joni_word_break_data.pl',
        output => 'third_party/joni/src/org/joni/WordBreakData.java',
        sources => ['auxiliary/WordBreakProperty.txt', 'emoji/emoji.txt'],
    },
    {
        name => 'joni-line-break',
        generator => 'dev/tools/generate_joni_line_break_data.pl',
        output => 'third_party/joni/src/org/joni/LineBreakData.java',
        sources => [qw(EastAsianWidth.txt LineBreak.txt ReadMe.txt UnicodeData.txt
            emoji/emoji.txt)],
    },
);

for my $contract (@contract) {
    my $name = $contract->{name};
    my $record = $entry{$name};
    ok ref($record) eq 'HASH', "$name has a manifest registration";
    is $record->{generator}, $contract->{generator}, "$name records its generator";
    is $record->{output}, $contract->{output}, "$name records its output";
    is_deeply [sort keys %{$record->{sources}}], [sort @{$contract->{sources}}],
        "$name records the complete input set";
    like $record->{output_sha256}, qr/\A[0-9a-f]{64}\z/,
        "$name records an output hash";

    my $generator = read_raw(path($record->{generator}));
    like $generator, qr/read_pinned_source/, "$name uses pinned source reads";
    like $generator, qr/verify_unicode_notice/,
        "$name validates Unicode notice material";
    my $output = read_raw(path($record->{output}));
    is sha256_hex($output), $record->{output_sha256},
        "$name checked-in output matches its manifest hash";
    my $copyright = encode_utf8('© 2025 Unicode®, Inc.');
    like $output, qr/\Q$copyright\E/,
        "$name output retains Unicode copyright";
    like $output,
        qr/Unicode and the Unicode Logo are registered trademarks of Unicode, Inc\./,
        "$name output retains the Unicode trademark notice";
    like $output, qr{https://www\.unicode\.org/terms_of_use\.html},
        "$name output retains the Unicode terms link";
    for my $relative (sort keys %{$record->{sources}}) {
        my $hash = $record->{sources}{$relative};
        like $generator, qr/\Q$hash\E/,
            "$name generator pins $relative";
        like $output, qr/\Q$hash\E/,
            "$name output records $relative provenance";
    }
}

my $unicode_root = $ENV{PERLONJAVA_UNICODE_ROOT}
    // File::Spec->catdir($repo, 'perl5', 'lib', 'unicore');
SKIP: {
    my %required = map { $_ => 1 } ('version', map { @{$_->{sources}} } @contract);
    skip 'complete selected Unicode source tree is unavailable', 18
        if grep { !-f File::Spec->catfile($unicode_root, split m{/}) }
            sort keys %required;
    for my $contract (@contract) {
        my $generator = path($contract->{generator});
        my ($first_status, $first) = run_generator($generator, $unicode_root);
        is $first_status, 0, "$contract->{name} first generation succeeds";
        my ($second_status, $second) = run_generator($generator, $unicode_root);
        is $second_status, 0, "$contract->{name} second generation succeeds";
        is $second, $first, "$contract->{name} generation is byte-deterministic";
        is $first, read_raw(path($contract->{output})),
            "$contract->{name} reproduces the checked-in output";

        my $temporary = tempdir(CLEANUP => 1);
        for my $relative (sort keys %required) {
            copy_source($unicode_root, $temporary, $relative);
        }
        my $corrupt_relative = $contract->{sources}[0];
        my $corrupt_path = File::Spec->catfile(
            $temporary, split m{/}, $corrupt_relative);
        open my $corrupt, '>>:raw', $corrupt_path
            or die "Cannot corrupt $corrupt_path: $!";
        print {$corrupt} "# corruption\n";
        close $corrupt or die "Cannot close $corrupt_path: $!";
        my ($corrupt_status, $corrupt_log) = run_generator($generator, $temporary);
        isnt $corrupt_status, 0, "$contract->{name} rejects input hash drift";
        like $corrupt_log, qr/\Q$corrupt_relative\E SHA-256 mismatch/,
            "$contract->{name} identifies the changed input";
    }
}

done_testing;

sub path {
    my ($relative) = @_;
    return File::Spec->catfile($repo, split m{/}, $relative);
}

sub read_raw {
    my ($file) = @_;
    open my $input, '<:raw', $file or die "Cannot read $file: $!";
    my $bytes = do { local $/; <$input> };
    close $input or die "Cannot close $file: $!";
    return $bytes;
}

sub write_raw {
    my ($file, $bytes) = @_;
    open my $output, '>:raw', $file or die "Cannot write $file: $!";
    print {$output} $bytes or die "Cannot write $file: $!";
    close $output or die "Cannot close $file: $!";
}

sub run_generator {
    my ($script, $source_root) = @_;
    local %ENV = %ENV;
    $ENV{PERLONJAVA_UNICODE_ROOT} = $source_root;
    my $error = gensym;
    my $pid = open3(undef, my $stdout, $error, $^X, $script);
    binmode $stdout, ':raw';
    binmode $error, ':raw';
    my $output = do { local $/; (<$stdout> // '') . (<$error> // '') };
    waitpid $pid, 0;
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
