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
    perl_language_version read_pinned_source read_raw read_unicode_version repo_root
    select_perl_root select_unicode_root verify_unicode_notice
);

my $check = 0;
my $list = 0;
my $refresh = 0;
my @only;
my $unicode_root;
my $perl_root;
my $root_override;
my $manifest_path = File::Spec->catfile($FindBin::Bin, 'perl_unicode_data_generators.json');
my $help = 0;
GetOptions(
    'check' => \$check,
    'list' => \$list,
    'refresh' => \$refresh,
    'only=s@' => \@only,
    'unicode-root=s' => \$unicode_root,
    'perl-root=s' => \$perl_root,
    'root=s' => \$root_override,
    'manifest=s' => \$manifest_path,
    'help' => \$help,
) or usage(2);
usage(0) if $help;
die "--refresh cannot be combined with --check or --list\n" if $refresh && ($check || $list);

my $root = defined $root_override ? File::Spec->rel2abs($root_override) : repo_root($FindBin::Bin);
my $manifest = decode_json(read_raw($manifest_path));
my $schema_version = $manifest->{schema_version} // 0;
die "$manifest_path has unsupported schema_version\n"
    unless $schema_version == 1 || $schema_version == 2;
my $current_checkout = $schema_version == 2
    && ($manifest->{perl_source_policy} // '') eq 'current-checkout';
die "$manifest_path schema 2 requires perl_source_policy current-checkout\n"
    if $schema_version == 2 && !$current_checkout;
die "--refresh requires schema 2 current-checkout provenance\n" if $refresh && !$current_checkout;
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
    version => $current_checkout ? 'current' : $manifest->{unicode_version},
    required => \@required,
);
my $actual_unicode_version;
if ($current_checkout) {
    my $version_text = read_raw(File::Spec->catfile($unicode_root, 'version'));
    $actual_unicode_version = $version_text;
    $actual_unicode_version =~ s/\s+\z//;
    die "Malformed current Unicode version '$actual_unicode_version'\n"
        unless $actual_unicode_version =~ /\A\d+\.\d+\.\d+\z/;
    for my $relative (sort keys %source_hash) {
        my $path = File::Spec->catfile($unicode_root, split m{/}, $relative);
        my $text = read_raw($path);
        verify_unicode_notice($path, $text);
    }
}
if ($refresh) {
    my $version_text = read_raw(File::Spec->catfile($unicode_root, 'version'));
    $manifest->{unicode_version} = $actual_unicode_version;
    $manifest->{version_sha256} = sha256_hex($version_text);
    for my $entry (@entries) {
        for my $field ('sources', 'perl_sources') {
            my $base = $field eq 'sources' ? $unicode_root : ($perl_root // select_perl_root(repo_root => $root, unicode_root => $unicode_root, required => [keys %{$entry->{$field} // {}}]));
            for my $relative (keys %{$entry->{$field} // {}}) {
                $entry->{$field}{$relative} = sha256_hex(read_raw(File::Spec->catfile($base, split m{/}, $relative)));
            }
        }
    }
    for my $relative (keys %{$manifest->{shared_sources} // {}}) {
        $manifest->{shared_sources}{$relative} = sha256_hex(read_raw(File::Spec->catfile($unicode_root, split m{/}, $relative)));
    }
    %source_hash = (%{$manifest->{shared_sources} // {}});
    %perl_source_hash = ();
    for my $entry (@entries) {
        $source_hash{$_} = $entry->{sources}{$_} for keys %{$entry->{sources} // {}};
        $perl_source_hash{$_} = $entry->{perl_sources}{$_} for keys %{$entry->{perl_sources} // {}};
    }
}
if (!$current_checkout) {
    read_unicode_version(
        path => File::Spec->catfile($unicode_root, 'version'),
        expected => $manifest->{unicode_version},
        sha256 => $manifest->{version_sha256},
    );
    for my $relative (sort keys %source_hash) {
        read_pinned_source(path => File::Spec->catfile($unicode_root, split m{/}, $relative),
            sha256 => $source_hash{$relative});
    }
}
if (%perl_source_hash) {
    $perl_root //= select_perl_root(
        repo_root => $root,
        unicode_root => $unicode_root,
        required => [sort keys %perl_source_hash],
    );
    if (!$current_checkout) {
        for my $relative (sort keys %perl_source_hash) {
            read_pinned_source(path => File::Spec->catfile($perl_root, split m{/}, $relative),
                sha256 => $perl_source_hash{$relative});
        }
    }
}
if ($current_checkout) {
    $perl_root //= select_perl_root(
        repo_root => $root, unicode_root => $unicode_root,
        required => ['patchlevel.h']);
    my $actual_commit = checkout_identity($perl_root, 'rev-parse', 'HEAD');
    my $actual_version = perl_language_version(root => $perl_root);
    if ($refresh) {
        $manifest->{perl_commit} = $actual_commit;
        $manifest->{perl_version} = $actual_version;
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
        unless $refresh || $actual_hash eq $entry->{output_sha256};
    $entry->{output_sha256} = $actual_hash if $refresh;

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
my @staged;
for my $item (@publication) {
    my ($entry, $output, $bytes, $hash) = @$item;
    my $directory = dirname($output);
    my ($temporary, $temporary_path) = tempfile('.unicode-data-XXXXXX', DIR => $directory, UNLINK => 0);
    binmode $temporary, ':raw';
    print {$temporary} $bytes or die "Cannot write $temporary_path: $!\n";
    close $temporary or die "Cannot close $temporary_path: $!\n";
    die "$entry->{name} staged SHA-256 changed before publication\n"
        unless sha256_hex(read_raw($temporary_path)) eq $hash;
    push @staged, [$entry, $output, $temporary_path, $hash];
}
my $manifest_temporary_path;
if ($refresh) {
    my ($temporary, $temporary_path) = tempfile('.unicode-manifest-XXXXXX', DIR => dirname($manifest_path), UNLINK => 0);
    print {$temporary} JSON::PP->new->canonical->pretty->encode($manifest) or die "Cannot write $temporary_path: $!\n";
    close $temporary or die "Cannot close $temporary_path: $!\n";
    $manifest_temporary_path = $temporary_path;
}
for my $item (@staged) {
    my ($entry, $output, $temporary_path, $hash) = @$item;
    rename $temporary_path, $output or die "Cannot publish $output: $!\n";
    print "$entry->{name}: updated ($hash)\n";
}
if ($refresh) {
    rename $manifest_temporary_path, $manifest_path or die "Cannot publish $manifest_path: $!\n";
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

sub checkout_identity {
    my ($root, @arguments) = @_;
    open my $pipe, '-|', 'git', '-C', $root, @arguments
        or die "Cannot inspect Perl checkout $root: $!\n";
    my $value = <$pipe> // '';
    close $pipe or die "Cannot inspect Perl checkout $root\n";
    chomp $value;
    die "Perl checkout $root has no Git identity\n" unless length $value;
    return $value;
}

sub usage {
    my ($exit) = @_;
    print <<'USAGE';
Usage: perl dev/tools/generate_perl_unicode_data.pl [options]
  --check                 verify generated files without changing them
  --refresh               regenerate outputs and transactionally refresh schema-2 provenance
  --only NAME             process one named generator (repeatable)
  --unicode-root PATH     use an explicit Perl unicore source directory
  --perl-root PATH        use an explicit selected/current Perl source checkout
  --root PATH             resolve manifest paths under PATH (testing/automation)
  --list                  list the complete generated-data inventory
  --help                  show this help
USAGE
    exit $exit;
}
