#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path);
use File::Copy qw(copy);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IPC::Open3;
use Symbol qw(gensym);
use Test::More;

my $root = abs_path(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $sync = File::Spec->catfile($root, 'dev', 'import-perl5', 'sync.pl');
my $config = File::Spec->catfile($root, 'dev', 'import-perl5', 'config.yaml');
my $makefile = File::Spec->catfile($root, 'Makefile');
my $name = File::Spec->catfile($root, 'src', 'main', 'perl', 'lib',
    'unicore', 'Name.pl');

my $configuration = read_file($config);
like($configuration,
    qr{source:\s*perl5/lib/unicore/mktables\s+target:\s*perl5/lib/unicore/Name\.pl\s+type:\s*generated_unicode_name_source}s,
    'full manifest generates the current-checkout Name.pl source');
like($configuration,
    qr{source:\s*perl5/lib/unicore/Name\.pl\s+target:\s*src/main/perl/lib/unicore/Name\.pl}s,
    'full manifest imports generated Name.pl into the bundled library');
is(git_output($root, 'ls-files', 'src/main/perl/lib/unicore/Name.pl'),
    'src/main/perl/lib/unicore/Name.pl', 'bundled Name.pl is tracked');
ok(-s $name, 'tracked bundled Name.pl is nonempty');
like(read_file($makefile),
    qr/^perl5-sync-check:\n\t\$\(PERL\) dev\/import-perl5\/update_perl5\.pl --sync --verify-idempotent/m,
    'development make gate updates latest upstream and verifies a second sync');

my $deterministic = fixture_project('deterministic', <<'YAML');
imports:
  - source: inputs/source.txt
    target: outputs/copied.txt
YAML
write_file(File::Spec->catfile($deterministic, 'inputs', 'source.txt'), "stable\n");
my ($status, $out, $err) = run_sync($deterministic, '--verify-idempotent');
is($status, 0, 'a deterministic full import passes second-sync verification')
    or diag $err;
like($out, qr/Idempotence verified:/,
    'successful verification records the second complete replay');

my $oscillating = fixture_project('oscillating', <<'YAML');
imports:
  - source: inputs/a.txt
    target: inputs/b.txt
  - source: inputs/c.txt
    target: inputs/a.txt
  - source: inputs/b.txt
    target: inputs/c.txt
YAML
write_file(File::Spec->catfile($oscillating, 'inputs', 'a.txt'), "a\n");
write_file(File::Spec->catfile($oscillating, 'inputs', 'b.txt'), "b\n");
write_file(File::Spec->catfile($oscillating, 'inputs', 'c.txt'), "c\n");
($status, $out, $err) = run_sync($oscillating, '--verify-idempotent');
isnt($status, 0, 'an oscillating import fails second-sync verification');
like($err, qr/second sync changed imported outputs/,
    'non-idempotence has a fail-closed diagnostic');
like($err, qr{inputs/[abc]\.txt},
    'non-idempotence diagnostic identifies a changed import target');

do $sync or die "Cannot load $sync: $@ $!";
my $unicode_root = tempdir('perlonjava-name-idempotence-XXXXXX',
    TMPDIR => 1, CLEANUP => 1);
my $unicode = File::Spec->catdir($unicode_root, 'perl5', 'lib', 'unicore');
make_path($unicode);
my $generator_relative = File::Spec->catfile(
    'perl5', 'lib', 'unicore', 'mktables');
my $generator = File::Spec->catfile($unicode_root, $generator_relative);
write_file($generator, <<'GENERATOR');
use strict;
use warnings;
use File::Spec;
my $directory;
while (@ARGV) {
    my $argument = shift;
    $directory = shift if $argument eq '-C';
}
die "missing -C\n" unless defined $directory;
open my $output, '>', File::Spec->catfile($directory, 'Name.pl') or die $!;
print {$output} "# generated from current checkout\nreturn { A => 0x41 };\n";
close $output or die $!;
GENERATOR
write_file(File::Spec->catfile($unicode, 'version'), "17.0.0\n");
write_file(File::Spec->catfile($unicode, 'UnicodeData.txt'),
    "0041;LATIN CAPITAL LETTER A\n");
my $generated_name = File::Spec->catfile($unicode, 'Name.pl');
SKIP: {
    skip 'current Unicode mktables requires system Perl 5.36+', 4
        if $^V lt v5.36.0;
    ok(generate_unicode_name_source($generator_relative, $generated_name,
            $unicode_root), 'Name.pl generation succeeds from current inputs');
    my $first = read_file($generated_name);
    ok(length($first), 'generated Name.pl is nonempty');
    ok(generate_unicode_name_source($generator_relative, $generated_name,
            $unicode_root), 'Name.pl generation is repeatable');
    is(read_file($generated_name), $first,
        'repeated Name.pl generation is byte-identical');
}

done_testing;

sub fixture_project {
    my ($name, $yaml) = @_;
    my $project = tempdir("perlonjava-sync-$name-XXXXXX",
        TMPDIR => 1, CLEANUP => 1);
    my $tool_directory = File::Spec->catdir($project, 'dev', 'import-perl5');
    make_path($tool_directory, File::Spec->catdir($project, 'inputs'));
    copy($sync, File::Spec->catfile($tool_directory, 'sync.pl'))
        or die "Cannot copy sync.pl: $!";
    write_file(File::Spec->catfile($tool_directory, 'config.yaml'), $yaml);
    return $project;
}

sub run_sync {
    my ($project, @arguments) = @_;
    my $script = File::Spec->catfile($project, 'dev', 'import-perl5', 'sync.pl');
    my $stderr = gensym;
    my $pid = open3(undef, my $stdout, $stderr, $^X, $script, @arguments);
    my $out = do { local $/; <$stdout> // '' };
    my $err = do { local $/; <$stderr> // '' };
    waitpid $pid, 0;
    return ($? >> 8, $out, $err);
}

sub git_output {
    my ($directory, @arguments) = @_;
    open my $pipe, '-|', 'git', '-C', $directory, @arguments or die $!;
    my $output = do { local $/; <$pipe> // '' };
    close $pipe or die "git @arguments failed\n";
    $output =~ s/\s+\z//;
    return $output;
}

sub write_file {
    my ($path, $contents) = @_;
    my (undef, $directory) = File::Spec->splitpath($path);
    make_path($directory) unless -d $directory;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!";
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    local $/;
    my $contents = <$fh> // '';
    close $fh or die "Cannot close $path: $!";
    return $contents;
}
