#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA;
use Fcntl qw(O_CREAT O_EXCL O_WRONLY);
use File::Basename qw(dirname basename);
use File::Spec;
use Getopt::Long qw(GetOptions Configure);
use JSON::PP;

use constant {
    MAX_CORPUS_MANIFEST_BYTES => 4 * 1024 * 1024,
    MAX_BASELINE_BYTES => 512 * 1024 * 1024,
    MAX_LAUNCHER_BYTES => 16 * 1024 * 1024,
    MAX_JAR_BYTES => 2 * 1024 * 1024 * 1024,
    MAX_SBOM_BYTES => 256 * 1024 * 1024,
    MAX_CORPUS_ARTIFACT_BYTES => 1024 * 1024 * 1024,
    MAX_CORPUS_TOTAL_BYTES => 8 * 1024 * 1024 * 1024,
    MAX_JSON_DEPTH => 64,
};

Configure(qw(no_auto_abbrev no_ignore_case require_order));
my (%option, $help);
GetOptions(
    'source-dir=s' => \$option{source},
    'perl5-dir=s' => \$option{perl5},
    'jperl=s' => \$option{jperl},
    'jcpan=s' => \$option{jcpan},
    'jar=s' => \$option{jar},
    'sbom=s' => \$option{sbom},
    'baseline=s' => \$option{baseline},
    'corpus-manifest=s' => \$option{corpus_manifest},
    'output=s' => \$option{output},
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;
for my $required (qw(source perl5 jperl jcpan jar sbom baseline corpus_manifest output)) {
    my $display = $required;
    $display =~ tr/_/-/;
    die "--$display is required\n"
        unless defined($option{$required}) && length($option{$required});
}

my %directory;
for my $name (qw(source perl5)) {
    $directory{$name} = canonical_directory($option{$name}, "$name checkout");
}
my %limit = (
    jperl => MAX_LAUNCHER_BYTES,
    jcpan => MAX_LAUNCHER_BYTES,
    jar => MAX_JAR_BYTES,
    sbom => MAX_SBOM_BYTES,
    baseline => MAX_BASELINE_BYTES,
    corpus_manifest => MAX_CORPUS_MANIFEST_BYTES,
);
my %file;
for my $name (qw(jperl jcpan jar sbom baseline corpus_manifest)) {
    $file{$name} = canonical_file($option{$name}, $name, $limit{$name});
}
die "jperl is not executable\n" unless -x $file{jperl}{path};
die "jcpan is not executable\n" unless -x $file{jcpan}{path};

my $expected_jperl = File::Spec->catfile($directory{source}, 'jperl');
my $expected_jcpan = File::Spec->catfile($directory{source}, 'jcpan');
die "jperl must be the tracked launcher in the selected source checkout\n"
    unless $file{jperl}{path} eq $expected_jperl;
die "jcpan must be the tracked launcher in the selected source checkout\n"
    unless $file{jcpan}{path} eq $expected_jcpan;
verify_tracked_file($directory{source}, 'jperl');
verify_tracked_file($directory{source}, 'jcpan');

my $source_commit = clean_checkout_commit($directory{source}, 'source');
my $perl5_commit = clean_checkout_commit($directory{perl5}, 'perl5');
my $corpus_bytes = read_snapshot($file{corpus_manifest}, 'corpus manifest');
reject_duplicate_json_keys($corpus_bytes, 'corpus manifest');
my $corpus = eval { JSON::PP->new->utf8->decode($corpus_bytes) };
die "Invalid corpus manifest JSON\n" unless ref($corpus) eq 'HASH' && !$@;
my $corpus_artifacts = validate_corpus_manifest($corpus, \%directory, \%file,
    $source_commit, $perl5_commit);

my $document = {
    schema_version => 1,
    mode => 'acceptance',
    identity => {
        source_commit => $source_commit,
        runner_commit => $source_commit,
        perl5_commit => $perl5_commit,
        jperl_sha256 => $file{jperl}{sha256},
        jar_sha256 => $file{jar}{sha256},
        sbom_sha256 => $file{sbom}{sha256},
    },
    inputs => {
        source => { path => $directory{source}, commit => $source_commit },
        perl5 => { path => $directory{perl5}, commit => $perl5_commit },
        jperl => { path => $file{jperl}{path}, sha256 => $file{jperl}{sha256} },
        jcpan => { path => $file{jcpan}{path}, sha256 => $file{jcpan}{sha256} },
        jar => { path => $file{jar}{path}, sha256 => $file{jar}{sha256} },
        sbom => { path => $file{sbom}{path}, sha256 => $file{sbom}{sha256} },
    },
};
assert_exact_keys($document, 'CPAN launch manifest',
    qw(schema_version mode identity inputs));
assert_exact_keys($document->{identity}, 'CPAN launch identity',
    qw(source_commit runner_commit perl5_commit jperl_sha256 jar_sha256 sbom_sha256));
assert_exact_keys($document->{inputs}, 'CPAN launch inputs',
    qw(source perl5 jperl jcpan jar sbom));

my $output_parent = canonical_directory(dirname($option{output}), 'output parent');
my $output = File::Spec->catfile($output_parent, basename($option{output}));
die "--output must be a canonical absolute path\n" unless $option{output} eq $output;
die "Refusing to overwrite launch manifest: $output\n" if -e $output || -l $output;
verify_all_inputs(\%directory, \%file, $corpus_artifacts,
    $source_commit, $perl5_commit);
publish_atomic($output, JSON::PP->new->utf8->canonical->pretty->encode($document));
verify_all_inputs(\%directory, \%file, $corpus_artifacts,
    $source_commit, $perl5_commit);
print "$output\n";

sub validate_corpus_manifest {
    my ($manifest, $directory, $file, $source_commit, $perl5_commit) = @_;
    assert_exact_keys($manifest, 'corpus manifest', qw(schema_version mode source
        identity baseline artifact_directory expected_files strict_regex_expected_files
        verified_runner_sha ledger_summary strict_regex_ledger_summary commands
        exit_statuses artifacts));
    die "Corpus manifest schema_version must be 1\n"
        unless ($manifest->{schema_version} // 0) == 1;
    die "Corpus manifest mode must be acceptance\n"
        unless ($manifest->{mode} // '') eq 'acceptance';
    die "Corpus manifest path must be artifact_directory/manifest.json\n"
        unless basename($file->{corpus_manifest}{path}) eq 'manifest.json';
    my $artifact_directory = canonical_directory(
        $manifest->{artifact_directory} // '', 'corpus artifact directory');
    die "Corpus manifest artifact directory differs from its path\n"
        unless $artifact_directory eq dirname($file->{corpus_manifest}{path});
    die "Corpus manifest has no complete corpus rows\n"
        unless positive_integer($manifest->{expected_files});
    die "Corpus manifest has no strict regex rows\n"
        unless positive_integer($manifest->{strict_regex_expected_files});
    die "Strict regex row count exceeds complete corpus row count\n"
        if $manifest->{strict_regex_expected_files} > $manifest->{expected_files};

    my $source = $manifest->{source};
    die "Corpus source identity is missing\n" unless ref($source) eq 'HASH';
    assert_exact_keys($source, 'corpus source identity', qw(starting_sha final_sha
        perl5_sha_as_provenance tracked_state_signature));
    for my $field (qw(starting_sha final_sha)) {
        die "Corpus $field differs from selected source commit\n"
            unless ($source->{$field} // '') eq $source_commit;
    }
    die "Corpus perl5 provenance differs from selected perl5 commit\n"
        unless ($source->{perl5_sha_as_provenance} // '') eq $perl5_commit;
    die "Corpus tracked-state signature is malformed\n"
        unless ($source->{tracked_state_signature} // '') =~ /\A[0-9a-f]{64}\z/;
    die "Corpus tracked-state signature does not describe a clean checkout\n"
        unless $source->{tracked_state_signature} eq Digest::SHA::sha256_hex('');

    my $identity = $manifest->{identity};
    die "Corpus identity is missing\n" unless ref($identity) eq 'HASH';
    assert_exact_keys($identity, 'corpus identity', qw(source_commit runner_commit
        perl5_commit launcher jar sbom baseline));
    die "Corpus source identity differs from selected source commit\n"
        unless ($identity->{source_commit} // '') eq $source_commit;
    die "Corpus runner identity differs from selected source commit\n"
        unless ($identity->{runner_commit} // '') eq $source_commit
            && ($manifest->{verified_runner_sha} // '') eq $source_commit;
    die "Corpus perl5 identity differs from selected perl5 commit\n"
        unless ($identity->{perl5_commit} // '') eq $perl5_commit;
    for my $binding (
        [launcher => 'jperl'], [jar => 'jar'], [sbom => 'sbom'],
        [baseline => 'baseline'],
    ) {
        my ($identity_name, $file_name) = @$binding;
        my $descriptor = $identity->{$identity_name};
        die "Corpus $identity_name descriptor is missing\n"
            unless ref($descriptor) eq 'HASH';
        assert_exact_keys($descriptor, "corpus $identity_name descriptor",
            qw(path sha256));
        die "Corpus $identity_name path differs from selected input\n"
            unless ($descriptor->{path} // '') eq $file->{$file_name}{path};
        die "Corpus $identity_name hash differs from selected input\n"
            unless ($descriptor->{sha256} // '') eq $file->{$file_name}{sha256};
    }
    die "Corpus baseline path differs from selected input\n"
        unless ($manifest->{baseline} // '') eq $file->{baseline}{path};

    my @required_commands = qw(jperl-version ledger strict-regex-ledger
        jvm-runner interpreter-runner jvm-comparison interpreter-comparison
        jvm-strict-regex-comparison interpreter-strict-regex-comparison packaging);
    my $commands = $manifest->{commands};
    die "Corpus command records are missing\n" unless ref($commands) eq 'ARRAY';
    my %command;
    for my $record (@$commands) {
        die "Corpus command record is malformed\n" unless ref($record) eq 'HASH';
        assert_exact_keys($record, 'corpus command record', qw(name argv environment));
        my $name = $record->{name} // '';
        die "Corpus command name is missing or duplicated\n"
            unless length($name) && !$command{$name};
        die "Corpus command argv or environment is malformed: $name\n"
            unless ref($record->{argv}) eq 'ARRAY' && @{$record->{argv}}
                && ref($record->{environment}) eq 'HASH';
        $command{$name} = $record;
    }
    assert_exact_keys(\%command, 'corpus command set', @required_commands);
    my $statuses = $manifest->{exit_statuses};
    assert_exact_keys($statuses, 'corpus exit-status set', @required_commands);
    for my $name (@required_commands) {
        die "Corpus command did not complete successfully: $name\n"
            unless defined($statuses->{$name}) && !ref($statuses->{$name})
                && $statuses->{$name} eq '0';
    }
    my $version = $command{'jperl-version'};
    die "Corpus jperl identity command differs from selected launcher\n"
        unless @{$version->{argv}} == 2
            && $version->{argv}[0] eq $file->{jperl}{path}
            && $version->{argv}[1] eq '-v';
    assert_exact_keys($version->{environment}, 'corpus jperl identity environment',
        qw(JPERL_UNIMPLEMENTED PERLONJAVA_JAR));
    die "Corpus jperl identity command was not bound to the selected JAR\n"
        unless ($version->{environment}{PERLONJAVA_JAR} // '') eq $file->{jar}{path};
    die "Corpus jperl identity command enabled unimplemented-feature warnings\n"
        unless exists($version->{environment}{JPERL_UNIMPLEMENTED})
            && !defined($version->{environment}{JPERL_UNIMPLEMENTED});

    my $artifacts = $manifest->{artifacts};
    die "Corpus artifact descriptors are missing\n"
        unless ref($artifacts) eq 'HASH' && keys %$artifacts;
    my @required_artifacts = qw(regex-ledger.json regex-files.txt
        strict-regex-ledger.json regex-scope-files.txt strict-regex-files.txt
        jvm-results.json interpreter-results.json jvm-comparison.json
        interpreter-comparison.json jvm-strict-regex-comparison.json
        interpreter-strict-regex-comparison.json ledger.log
        strict-regex-ledger.log jvm-runner.log interpreter-runner.log
        jvm-comparison.log interpreter-comparison.log
        jvm-strict-regex-comparison.log interpreter-strict-regex-comparison.log
        packaging.log jperl-version.log);
    assert_exact_keys($artifacts, 'corpus artifact set', @required_artifacts);
    my $total = 0;
    my %seen_path;
    my @records;
    for my $name (sort keys %$artifacts) {
        die "Corpus artifact name is unsafe: $name\n"
            unless $name =~ /\A[A-Za-z0-9][A-Za-z0-9_.-]*\z/;
        my $descriptor = $artifacts->{$name};
        die "Corpus artifact descriptor is malformed: $name\n"
            unless ref($descriptor) eq 'HASH';
        assert_exact_keys($descriptor, "corpus artifact $name", qw(path sha256));
        my $record = canonical_file($descriptor->{path} // '',
            "corpus artifact $name", MAX_CORPUS_ARTIFACT_BYTES);
        die "Corpus artifact escapes the artifact directory: $name\n"
            unless dirname($record->{path}) eq $artifact_directory;
        die "Corpus artifact path is duplicated: $record->{path}\n"
            if $seen_path{$record->{path}}++;
        die "Corpus artifact basename differs from its key: $name\n"
            unless basename($record->{path}) eq $name;
        die "Corpus artifact hash mismatch: $name\n"
            unless ($descriptor->{sha256} // '') eq $record->{sha256};
        $total += $record->{size};
        die "Corpus artifact set exceeds the bounded byte budget\n"
            if $total > MAX_CORPUS_TOTAL_BYTES;
        push @records, $record;
    }
    my ($version_record) = grep { basename($_->{path}) eq 'jperl-version.log' }
        @records;
    my $version_text = read_snapshot($version_record, 'retained jperl version log');
    my @reported = $version_text =~ /\b([0-9a-f]{7,40})\b/ig;
    die "Retained jperl version log does not bind the selected source commit\n"
        unless grep { index($source_commit, lc($_)) == 0 } @reported;
    return \@records;
}

sub verify_all_inputs {
    my ($directory, $file, $corpus_artifacts, $source_commit, $perl5_commit) = @_;
    die "Source checkout changed during launch-manifest preparation\n"
        unless clean_checkout_commit($directory->{source}, 'source') eq $source_commit;
    die "perl5 checkout changed during launch-manifest preparation\n"
        unless clean_checkout_commit($directory->{perl5}, 'perl5') eq $perl5_commit;
    for my $name (sort keys %$file) {
        my $current = canonical_file($file->{$name}{path}, $name, $limit{$name});
        die "Selected input changed during launch-manifest preparation: $name\n"
            unless same_record($current, $file->{$name});
    }
    for my $record (@$corpus_artifacts) {
        my $current = canonical_file($record->{path}, 'corpus artifact',
            MAX_CORPUS_ARTIFACT_BYTES);
        die "Retained corpus artifact changed during launch-manifest preparation\n"
            unless same_record($current, $record);
    }
    verify_tracked_file($directory->{source}, 'jperl');
    verify_tracked_file($directory->{source}, 'jcpan');
}

sub canonical_file {
    my ($path, $label, $maximum) = @_;
    die "$label path must be absolute\n"
        unless defined($path) && File::Spec->file_name_is_absolute($path);
    my @stat = lstat($path);
    die "$label is missing\n" unless @stat;
    die "$label must be a nonsymlink regular file\n"
        unless -f _ && !-l _;
    my $resolved = abs_path($path);
    die "$label path must be canonical and contain no symlink components\n"
        unless defined($resolved) && $resolved eq $path;
    die "$label is empty\n" unless $stat[7] > 0;
    die "$label exceeds its bounded byte limit\n" if $stat[7] > $maximum;
    my $sha = sha256_file($path, $maximum, $label);
    my @after = lstat($path);
    die "$label changed while it was read\n"
        unless @after && !-l _ && $after[0] == $stat[0] && $after[1] == $stat[1]
            && $after[2] == $stat[2] && $after[7] == $stat[7]
            && $after[9] == $stat[9];
    return { path => $path, sha256 => $sha, size => 0 + $stat[7],
        dev => 0 + $stat[0], inode => 0 + $stat[1], mode => 0 + $stat[2],
        mtime => 0 + $stat[9] };
}

sub canonical_directory {
    my ($path, $label) = @_;
    die "$label path must be absolute\n"
        unless defined($path) && File::Spec->file_name_is_absolute($path);
    my @stat = lstat($path);
    die "$label is missing or is not a directory\n" unless @stat && -d _;
    die "$label must not be a symlink\n" if -l _;
    my $resolved = abs_path($path);
    die "$label path must be canonical and contain no symlink components\n"
        unless defined($resolved) && $resolved eq $path;
    return $resolved;
}

sub clean_checkout_commit {
    my ($directory, $label) = @_;
    my $commit = capture_bounded(['git', '-C', $directory, 'rev-parse', '--verify',
        'HEAD^{commit}'], 256, "$label commit");
    $commit =~ s/\s+\z//;
    die "$label checkout HEAD is not a full Git commit\n"
        unless $commit =~ /\A[0-9a-f]{40}\z/;
    my $status = capture_bounded(['git', '-C', $directory, 'status', '--porcelain',
        '--untracked-files=no'], 1024 * 1024, "$label status");
    die "$label checkout tracked state is dirty\n" if length $status;
    return $commit;
}

sub verify_tracked_file {
    my ($source, $relative) = @_;
    capture_bounded(['git', '-C', $source, 'ls-files', '--error-unmatch', '--',
        $relative], 4096, "tracked $relative");
}

sub capture_bounded {
    my ($argv, $maximum, $label) = @_;
    open my $fh, '-|', @$argv or die "Cannot execute $argv->[0]: $!\n";
    binmode $fh, ':raw';
    my $bytes = '';
    while (1) {
        my $read = read($fh, my $chunk, 8192);
        die "Cannot read $label: $!\n" unless defined $read;
        last unless $read;
        $bytes .= $chunk;
        die "$label output exceeds its bounded byte limit\n"
            if length($bytes) > $maximum;
    }
    close $fh or die "$label command failed\n";
    return $bytes;
}

sub sha256_file {
    my ($path, $maximum, $label) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $label: $!\n";
    my $digest = Digest::SHA->new(256);
    my $total = 0;
    while (1) {
        my $read = read($fh, my $chunk, 1024 * 1024);
        die "Cannot read $label: $!\n" unless defined $read;
        last unless $read;
        $total += $read;
        die "$label exceeds its bounded byte limit\n" if $total > $maximum;
        $digest->add($chunk);
    }
    close $fh or die "Cannot close $label: $!\n";
    return $digest->hexdigest;
}

sub read_snapshot {
    my ($record, $label) = @_;
    open my $fh, '<:raw', $record->{path} or die "Cannot read $label: $!\n";
    local $/;
    my $bytes = <$fh>;
    close $fh or die "Cannot close $label: $!\n";
    die "$label changed while it was read\n"
        unless length($bytes) == $record->{size}
            && Digest::SHA::sha256_hex($bytes) eq $record->{sha256};
    return $bytes;
}

sub same_record {
    my ($left, $right) = @_;
    return $left->{path} eq $right->{path}
        && $left->{sha256} eq $right->{sha256}
        && $left->{size} == $right->{size}
        && $left->{dev} == $right->{dev}
        && $left->{inode} == $right->{inode}
        && $left->{mode} == $right->{mode}
        && $left->{mtime} == $right->{mtime};
}

sub assert_exact_keys {
    my ($value, $label, @expected) = @_;
    die "$label must be an object\n" unless ref($value) eq 'HASH';
    my @actual = sort keys %$value;
    @expected = sort @expected;
    die "$label has missing, extra, or duplicate fields\n"
        unless JSON::PP->new->canonical->encode(\@actual)
            eq JSON::PP->new->canonical->encode(\@expected);
}

sub reject_duplicate_json_keys {
    my ($bytes, $label) = @_;
    pos($bytes) = 0;
    parse_json_value(\$bytes, 0, $label);
    skip_json_space(\$bytes);
    die "$label has trailing JSON data\n" unless pos($bytes) == length($bytes);
}

sub parse_json_value {
    my ($source, $depth, $label) = @_;
    die "$label exceeds the maximum JSON nesting depth\n"
        if $depth > MAX_JSON_DEPTH;
    skip_json_space($source);
    my $next = substr($$source, pos($$source), 1);
    if ($next eq '{') {
        pos($$source)++;
        skip_json_space($source);
        my %seen;
        return pos($$source)++ if substr($$source, pos($$source), 1) eq '}';
        while (1) {
            my $key = parse_json_string($source, $label);
            die "$label contains duplicate JSON key: $key\n" if $seen{$key}++;
            skip_json_space($source);
            die "$label has malformed JSON object syntax\n"
                unless substr($$source, pos($$source)++, 1) eq ':';
            parse_json_value($source, $depth + 1, $label);
            skip_json_space($source);
            my $separator = substr($$source, pos($$source)++, 1);
            last if $separator eq '}';
            die "$label has malformed JSON object syntax\n" unless $separator eq ',';
            skip_json_space($source);
        }
        return;
    }
    if ($next eq '[') {
        pos($$source)++;
        skip_json_space($source);
        return pos($$source)++ if substr($$source, pos($$source), 1) eq ']';
        while (1) {
            parse_json_value($source, $depth + 1, $label);
            skip_json_space($source);
            my $separator = substr($$source, pos($$source)++, 1);
            last if $separator eq ']';
            die "$label has malformed JSON array syntax\n" unless $separator eq ',';
        }
        return;
    }
    if ($next eq '"') {
        parse_json_string($source, $label);
        return;
    }
    $$source =~ /\G(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/gc
        or die "$label has malformed JSON value\n";
}

sub parse_json_string {
    my ($source, $label) = @_;
    my $start = pos($$source);
    die "$label has malformed JSON string\n"
        unless substr($$source, $start, 1) eq '"';
    pos($$source)++;
    while (pos($$source) < length($$source)) {
        my $character = substr($$source, pos($$source)++, 1);
        if ($character eq '"') {
            my $token = substr($$source, $start, pos($$source) - $start);
            my $decoded = eval { JSON::PP->new->decode($token) };
            die "$label has malformed JSON string\n" if $@ || ref($decoded);
            return $decoded;
        }
        die "$label has a control character in a JSON string\n"
            if ord($character) < 0x20;
        if ($character eq '\\') {
            my $escape = substr($$source, pos($$source)++, 1);
            die "$label has malformed JSON escape\n" unless $escape =~ /["\\\/bfnrtu]/;
            if ($escape eq 'u') {
                my $hex = substr($$source, pos($$source), 4);
                die "$label has malformed JSON Unicode escape\n"
                    unless $hex =~ /\A[0-9a-fA-F]{4}\z/;
                pos($$source) += 4;
            }
        }
    }
    die "$label has unterminated JSON string\n";
}

sub skip_json_space {
    my ($source) = @_;
    $$source =~ /\G[\x20\x09\x0a\x0d]*/gc;
}

sub positive_integer {
    return defined($_[0]) && !ref($_[0]) && $_[0] =~ /\A[1-9][0-9]*\z/;
}

sub publish_atomic {
    my ($output, $bytes) = @_;
    my $temporary = "$output.tmp.$$";
    die "Temporary output path already exists: $temporary\n"
        if -e $temporary || -l $temporary;
    sysopen my $fh, $temporary, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot create temporary launch manifest: $!\n";
    print {$fh} $bytes or die "Cannot write temporary launch manifest: $!\n";
    close $fh or die "Cannot close temporary launch manifest: $!\n";
    rename $temporary, $output
        or die "Cannot publish launch manifest atomically: $!\n";
}

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: prepare_phase36_cpan_launch_manifest.pl \
  --source-dir ABS --perl5-dir ABS --jperl ABS --jcpan ABS \
  --jar ABS --sbom ABS --baseline ABS --corpus-manifest ABS --output ABS

Create the canonical, fail-closed schema_version 1 launch manifest consumed by
run_phase36_cpan_acceptance.pl. Every path must be canonical and absolute. The
completed regex corpus manifest supplies identity assertions only; this tool
rereads, hashes, and independently verifies every selected input before atomic
publication. It never executes jperl or jcpan.
USAGE
    exit $status;
}
