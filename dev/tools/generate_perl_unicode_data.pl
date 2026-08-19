#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname);
use File::Spec;
use File::Temp qw(tempfile);
use FindBin;
use Getopt::Long qw(GetOptions);
use JSON::PP qw(decode_json);
use lib File::Spec->catdir($FindBin::Bin, 'lib');
use PerlOnJava::UnicodeGenerator qw(
    read_pinned_source read_raw read_unicode_version repo_root select_perl_root select_unicode_root
);

my $check = 0;
my $list = 0;
my @only;
my $unicode_root;
my $perl_root;
my $root_override;
my $manifest_path = File::Spec->catfile($FindBin::Bin, 'perl_unicode_data_generators.json');
my $help = 0;
GetOptions(
    'check' => \$check,
    'list' => \$list,
    'only=s@' => \@only,
    'unicode-root=s' => \$unicode_root,
    'perl-root=s' => \$perl_root,
    'root=s' => \$root_override,
    'manifest=s' => \$manifest_path,
    'help' => \$help,
) or usage(2);
usage(0) if $help;

my $root = defined $root_override ? File::Spec->rel2abs($root_override) : repo_root($FindBin::Bin);
my $manifest = decode_json(read_raw($manifest_path));
die "$manifest_path has unsupported schema_version\n"
    unless ($manifest->{schema_version} // 0) == 1;
my @entries = @{$manifest->{generators} // []};
die "$manifest_path defines no generators\n" unless @entries;

my %by_name;
my %by_output;
for my $entry (@entries) {
    my $name = $entry->{name} // die "$manifest_path has a generator without a name\n";
    die "$manifest_path repeats generator name '$name'\n" if $by_name{$name}++;
    safe_relative_path($entry->{generator}, "$name generator");
    safe_relative_path($entry->{output}, "$name output");
    die "$manifest_path repeats output '$entry->{output}'\n" if $by_output{$entry->{output}}++;
    die "$name has an invalid output_sha256\n"
        unless ($entry->{output_sha256} // '') =~ /\A[0-9a-f]{64}\z/;
    for my $relative (keys %{$entry->{sources} // {}}) {
        safe_relative_path($relative, "$name source");
        die "$name has an invalid source SHA-256 for $relative\n"
            unless $entry->{sources}{$relative} =~ /\A[0-9a-f]{64}\z/;
    }
    for my $relative (keys %{$entry->{perl_sources} // {}}) {
        safe_relative_path($relative, "$name Perl source");
        die "$name has an invalid Perl source SHA-256 for $relative\n"
            unless $entry->{perl_sources}{$relative} =~ /\A[0-9a-f]{64}\z/;
    }
}
if (@only) {
    my %wanted = map { $_ => 1 } @only;
    my %known = map { $_->{name} => 1 } @entries;
    die "Unknown generator name(s): " . join(', ', sort grep { !$known{$_} } keys %wanted) . "\n"
        if grep { !$known{$_} } keys %wanted;
    @entries = grep { $wanted{$_->{name}} } @entries;
}

if ($list) {
    print join("\t", qw(NAME GENERATOR OUTPUT SHA256)), "\n";
    print join("\t", @$_{qw(name generator output output_sha256)}), "\n" for @entries;
    exit 0;
}

my %source_hash = (%{$manifest->{shared_sources} // {}});
for my $relative (keys %source_hash) {
    safe_relative_path($relative, 'shared source');
    die "Invalid shared source SHA-256 for $relative\n"
        unless $source_hash{$relative} =~ /\A[0-9a-f]{64}\z/;
}
for my $entry (@entries) {
    while (my ($relative, $hash) = each %{$entry->{sources} // {}}) {
        die "Conflicting SHA-256 pins for $relative\n"
            if exists $source_hash{$relative} && $source_hash{$relative} ne $hash;
        $source_hash{$relative} = $hash;
    }
}
my %perl_source_hash;
for my $entry (@entries) {
    while (my ($relative, $hash) = each %{$entry->{perl_sources} // {}}) {
        die "Conflicting Perl source SHA-256 pins for $relative\n"
            if exists $perl_source_hash{$relative} && $perl_source_hash{$relative} ne $hash;
        $perl_source_hash{$relative} = $hash;
    }
}
my @required = ('version', sort keys %source_hash);
$unicode_root //= select_unicode_root(
    repo_root => $root,
    version => $manifest->{unicode_version},
    required => \@required,
);
read_unicode_version(
    path => File::Spec->catfile($unicode_root, 'version'),
    expected => $manifest->{unicode_version},
    sha256 => $manifest->{version_sha256},
);
for my $relative (sort keys %source_hash) {
    read_pinned_source(
        path => File::Spec->catfile($unicode_root, split m{/}, $relative),
        sha256 => $source_hash{$relative},
    );
}
if (%perl_source_hash) {
    $perl_root //= select_perl_root(
        repo_root => $root,
        unicode_root => $unicode_root,
        required => [sort keys %perl_source_hash],
    );
    for my $relative (sort keys %perl_source_hash) {
        read_pinned_source(
            path => File::Spec->catfile($perl_root, split m{/}, $relative),
            sha256 => $perl_source_hash{$relative},
        );
    }
}

my @publication;
my $failed = 0;
for my $entry (@entries) {
    my $script = File::Spec->catfile($root, split m{/}, $entry->{generator});
    my $output = File::Spec->catfile($root, split m{/}, $entry->{output});
    die "Missing generator $script\n" unless -f $script;

    my $first = run_generator($script, $unicode_root, $perl_root);
    my $second = run_generator($script, $unicode_root, $perl_root);
    die "$entry->{name} generator is nondeterministic across consecutive runs\n"
        unless $first eq $second;
    my $actual_hash = sha256_hex($first);
    die "$entry->{name} generated SHA-256 mismatch: expected $entry->{output_sha256}, found $actual_hash\n"
        unless $actual_hash eq $entry->{output_sha256};

    my $current = -f $output ? read_raw($output) : undef;
    if (defined $current && $current eq $first) {
        print "$entry->{name}: current ($actual_hash)\n";
        next;
    }
    if ($check) {
        warn "$entry->{name}: stale generated output $entry->{output}\n";
        $failed = 1;
        next;
    }
    push @publication, [$entry, $output, $first, $actual_hash];
}

exit 1 if $failed;
for my $item (@publication) {
    my ($entry, $output, $bytes, $hash) = @$item;
    my $directory = dirname($output);
    my ($temporary, $temporary_path) = tempfile('.unicode-data-XXXXXX', DIR => $directory, UNLINK => 0);
    binmode $temporary, ':raw';
    print {$temporary} $bytes or die "Cannot write $temporary_path: $!\n";
    close $temporary or die "Cannot close $temporary_path: $!\n";
    die "$entry->{name} staged SHA-256 changed before publication\n"
        unless sha256_hex(read_raw($temporary_path)) eq $hash;
    rename $temporary_path, $output or die "Cannot publish $output: $!\n";
    print "$entry->{name}: updated ($hash)\n";
}

sub run_generator {
    my ($script, $source_root, $pinned_perl_root) = @_;
    local %ENV = %ENV;
    $ENV{PERLONJAVA_UNICODE_ROOT} = $source_root;
    if (defined $pinned_perl_root) {
        $ENV{PERLONJAVA_PERL_ROOT} = $pinned_perl_root;
    } else {
        delete $ENV{PERLONJAVA_PERL_ROOT};
    }
    open my $pipe, '-|', $^X, $script or die "Cannot run $script: $!\n";
    binmode $pipe, ':raw';
    local $/;
    my $bytes = <$pipe>;
    close $pipe or die "$script failed with exit " . ($? >> 8) . "\n";
    return $bytes;
}

sub safe_relative_path {
    my ($path, $label) = @_;
    die "$label path is missing\n" unless defined $path && length $path;
    die "$label path must be relative: $path\n" if File::Spec->file_name_is_absolute($path);
    die "$label path contains parent traversal: $path\n"
        if grep { $_ eq '..' } File::Spec->splitdir($path);
    return $path;
}

sub usage {
    my ($exit) = @_;
    print <<'USAGE';
Usage: perl dev/tools/generate_perl_unicode_data.pl [options]
  --check                 verify generated files without changing them
  --only NAME             process one named generator (repeatable)
  --unicode-root PATH     use an explicit Perl unicore source directory
  --perl-root PATH        use an explicit pinned Perl source checkout
  --root PATH             resolve manifest paths under PATH (testing/automation)
  --list                  list the complete generated-data inventory
  --help                  show this help
USAGE
    exit $exit;
}
