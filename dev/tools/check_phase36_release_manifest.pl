#!/usr/bin/env perl

use strict;
use warnings;

use Cwd qw(abs_path getcwd);
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname);
use File::Find qw(find);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Fcntl qw(:DEFAULT :mode);
use Getopt::Long qw(GetOptionsFromArray);
use IO::Handle;
use JSON::PP;

my $FORK_REF = 'pkg:generic/perlonjava/joni-fork@2.2.7';
my $LEGACY_REF = 'pkg:maven/org.jruby.joni/joni@2.2.7?type=jar';
my $JCODINGS_REF = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';
my $TOOL_DIR = dirname(abs_path(__FILE__))
    or die "Cannot resolve release wrapper directory\n";

our $EXECUTABLE = !caller;
exit main(@ARGV) if $EXECUTABLE;

sub main {
    my (@arguments) = @_;
    my ($evidence, $expected_commit, $output, $help);
    GetOptionsFromArray(
        \@arguments,
        'evidence=s' => \$evidence,
        'expected-commit=s' => \$expected_commit,
        'output=s' => \$output,
        'help' => \$help,
    ) or usage(2);
    usage(0) if $help;
    usage(2) if @arguments;
    die "--evidence is required\n" unless defined($evidence) && length($evidence);
    die "--expected-commit must be a full Git SHA\n"
        unless defined($expected_commit)
            && $expected_commit =~ /\A[0-9a-f]{40}\z/;
    die "Refusing to overwrite output $output\n"
        if defined($output) && -e $output;

    my $sealed = seal_evidence($evidence);
    my $evidence_path = $sealed->{original_evidence};
    my $evidence_sha256 = sha256_hex($sealed->{evidence_bytes});
    my $document = decode_json_object($sealed->{evidence_bytes},
        'acceptance evidence', $evidence_path);
    assert_legacy_artifacts_confined($document, $sealed->{original_root});
    my $legacy = run_legacy_checker($sealed->{snapshot_evidence}, $expected_commit);
    die "Legacy acceptance checker did not produce an authoritative strict report\n"
        unless true_value($legacy->{summary}{authoritative})
            && ($legacy->{check_mode} // '') eq 'strict'
            && ($legacy->{expected_commit} // '') eq $expected_commit;

    my $strict = verify_strict_notice_artifact(
        $evidence_path, $document, $expected_commit, $sealed);

    my $report = {
        schema_version => 1,
        kind => 'phase36-release-manifest',
        authoritative => JSON::PP::true,
        source_commit => $expected_commit,
        evidence_path => $evidence_path,
        evidence_sha256 => $evidence_sha256,
        legacy_checker => {
            check_mode => 'strict',
            authoritative => JSON::PP::true,
        },
        strict_notice_license => $strict,
    };
    my $rendered = JSON::PP->new->utf8->canonical->pretty->encode($report);
    if (defined $output) {
        publish_atomic($output, $rendered);
    } else {
        checked_print(\*STDOUT, $rendered, 'standard output');
        checked_flush(\*STDOUT, 'standard output');
        checked_close(\*STDOUT, 'standard output') if $EXECUTABLE;
    }
    return 0;
}

sub run_legacy_checker {
    my ($evidence, $expected_commit) = @_;
    my $checker = File::Spec->catfile($TOOL_DIR,
        'check_phase36_acceptance_manifest.pl');
    my $requirements = File::Spec->catfile($TOOL_DIR,
        'phase36_acceptance_requirements.json');
    existing_file($checker, 'legacy acceptance checker');
    existing_file($requirements, 'acceptance requirements');
    my $directory = tempdir(CLEANUP => 1);
    my $report = File::Spec->catfile($directory, 'strict-report.json');
    my $status = system($^X, $checker,
        '--requirements', $requirements,
        '--evidence', $evidence,
        '--mode', 'strict',
        '--expected-commit', $expected_commit,
        '--output', $report);
    die "Cannot execute legacy acceptance checker: $!\n" if $status == -1;
    die "Legacy acceptance checker was terminated by signal " . ($status & 127) . "\n"
        if $status & 127;
    die "Legacy acceptance checker rejected the release manifest\n"
        if ($status >> 8) != 0;
    return load_json($report, 'legacy strict acceptance report');
}

sub verify_strict_notice_artifact {
    my ($evidence_path, $evidence, $expected_commit, $sealed) = @_;
    $sealed //= seal_evidence($evidence_path);
    die "Strict notice verification expected commit is missing or malformed\n"
        unless defined($expected_commit) && !ref($expected_commit)
            && $expected_commit =~ /\A[0-9a-f]{40}\z/;
    die "Acceptance evidence schema_version must be 1\n"
        unless ($evidence->{schema_version} // 0) == 1;
    die "Acceptance evidence mode must be acceptance\n"
        unless ($evidence->{mode} // '') eq 'acceptance';
    my $identity = require_hash($evidence->{identity}, 'acceptance identity');
    die "Acceptance identity source commit differs from expected release commit\n"
        unless ($identity->{source_commit} // '') eq $expected_commit;
    my $gates = require_hash($evidence->{gates}, 'acceptance gates');
    my $gate = require_hash($gates->{'notice-license'}, 'notice-license gate');
    die "Notice-license gate did not pass\n" unless ($gate->{state} // '') eq 'passed';
    my $artifact = require_hash($gate->{artifact}, 'notice-license artifact');
    my $artifact_sha = require_sha($artifact->{sha256}, 'notice-license artifact SHA-256');
    my $root = $sealed->{original_root};
    my $artifact_path = resolve_under_root($artifact->{path}, $root,
        'notice-license artifact');
    my $artifact_snapshot = snapshot_path($sealed, $artifact_path,
        'notice-license artifact');
    die "Notice-license artifact hash mismatch\n"
        unless sha256_file($artifact_snapshot) eq $artifact_sha;
    my $record = load_json($artifact_snapshot, 'strict notice-license artifact');
    die "Notice-license artifact schema_version must be 1\n"
        unless ($record->{schema_version} // 0) == 1;
    die "Notice-license artifact has the wrong kind\n"
        unless ($record->{kind} // '') eq 'notice-license';
    die "Notice-license artifact is not verified\n"
        unless true_value($record->{verified});
    for my $field (qw(missing_notices changed_notices missing_licenses changed_licenses)) {
        die "Notice-license artifact $field is missing or nonzero\n"
            unless number($record->{$field}) && $record->{$field} == 0;
    }
    die "Notice-license gate details differ from the sealed verifier artifact\n"
        unless canonical($gate->{details}) eq canonical($record);

    my $sealed_jar_sha = require_sha($identity->{jar_sha256},
        'sealed JAR SHA-256');
    my $sealed_sbom_sha = require_sha($identity->{sbom_sha256},
        'sealed SBOM SHA-256');
    die "Notice-license report JAR hash differs from sealed identity\n"
        unless ($record->{jar_sha256} // '') eq $sealed_jar_sha;
    die "Notice-license report SBOM hash differs from sealed identity\n"
        unless ($record->{sbom_sha256} // '') eq $sealed_sbom_sha;
    my $jar = absolute_report_file($record->{jar_path}, 'reported standalone JAR');
    my $sbom_file = absolute_report_file($record->{sbom_path}, 'reported external SBOM');
    my $jar_snapshot = private_snapshot($sealed, $jar, 'standalone.jar',
        'reported standalone JAR');
    my $sbom_snapshot = private_snapshot($sealed, $sbom_file, 'sbom.json',
        'reported external SBOM');
    die "Standalone JAR hash differs from sealed identity\n"
        unless sha256_file($jar_snapshot) eq $sealed_jar_sha;
    die "External SBOM hash differs from sealed identity\n"
        unless sha256_file($sbom_snapshot) eq $sealed_sbom_sha;

    assert_report_contract($record);
    assert_strict_verifier_replay(
        $record, $artifact_snapshot, $jar_snapshot, $sbom_snapshot,
        $expected_commit, $sealed);
    my $sbom_bytes = read_raw($sbom_snapshot);
    assert_embedded_sbom($jar_snapshot, $sbom_bytes);

    return {
        verified => JSON::PP::true,
        contract => 'joni-fork-strict-v1',
        artifact_path => $artifact_path,
        artifact_sha256 => $artifact_sha,
        jar_path => $jar,
        jar_sha256 => $sealed_jar_sha,
        sbom_path => $sbom_file,
        sbom_sha256 => $sealed_sbom_sha,
        embedded_sbom_entries => 1,
        embedded_sbom_byte_equal => JSON::PP::true,
    };
}

sub assert_strict_verifier_replay {
    my ($record, $artifact, $jar, $sbom, $expected_commit, $sealed) = @_;
    die "Strict verifier replay expected commit is missing or malformed\n"
        unless defined($expected_commit) && !ref($expected_commit)
            && $expected_commit =~ /\A[0-9a-f]{40}\z/;
    assert_external_sbom(
        load_json($sbom, 'replayed external SBOM'), $expected_commit);
    my $source_root = $record->{source_root};
    die "Notice-license report source_root is not absolute\n"
        unless defined($source_root) && !ref($source_root)
            && File::Spec->file_name_is_absolute($source_root);
    $source_root = abs_path($source_root)
        or die "Cannot resolve notice-license report source_root\n";
    die "Notice-license report source_root is not a directory\n" unless -d $source_root;
    my $source_snapshot = snapshot_notice_sources($sealed, $source_root);
    my $verifier = File::Spec->catfile($TOOL_DIR,
        'verify_phase36_notice_license.pl');
    existing_file($verifier, 'strict notice-license verifier');
    my $directory = tempdir(CLEANUP => 1);
    my $replayed = File::Spec->catfile($directory, 'notice-license.json');
    my ($status, $text) = capture_command($^X, $verifier, '--strict',
        '--source-root', $source_snapshot, '--jar', $jar, '--sbom', $sbom,
        '--output', $replayed);
    die "Strict notice-license verifier replay rejected the sealed artifacts:\n$text"
        if $status != 0;
    my $replay = load_json($replayed, 'replayed notice-license artifact');
    $replay->{jar_path} = $record->{jar_path};
    $replay->{sbom_path} = $record->{sbom_path};
    $replay->{source_root} = $record->{source_root};
    my %record_notice = map { ($_->{id} // '') => $_ }
        @{ref($record->{notices}) eq 'ARRAY' ? $record->{notices} : []};
    for my $notice (@{ref($replay->{notices}) eq 'ARRAY' ? $replay->{notices} : []}) {
        my $expected = $record_notice{$notice->{id} // ''};
        die "Strict notice-license verifier replay notice set differs\n"
            unless $expected;
        $notice->{path} = $expected->{path};
    }
    die "Sealed notice-license artifact differs from strict verifier replay\n"
        unless canonical($replay) eq canonical($record);
}

sub capture_command {
    my (@command) = @_;
    pipe my $read, my $write or die "Cannot create verifier output pipe: $!\n";
    my $pid = fork();
    die "Cannot fork strict verifier: $!\n" unless defined $pid;
    if ($pid == 0) {
        close $read;
        open STDOUT, '>&', $write or die "Cannot capture verifier stdout: $!\n";
        open STDERR, '>&', $write or die "Cannot capture verifier stderr: $!\n";
        close $write;
        exec { $command[0] } @command;
        die "Cannot execute $command[0]: $!\n";
    }
    close $write;
    my $text = do { local $/; <$read> };
    close $read or die "Cannot close verifier output pipe: $!\n";
    waitpid($pid, 0);
    my $status = $?;
    die "Strict notice-license verifier was terminated by signal "
        . ($status & 127) . "\n" if $status & 127;
    return ($status >> 8, $text // '');
}

sub assert_report_contract {
    my ($record) = @_;
    my $components = $record->{components};
    die "Notice-license report components must contain exactly Joni fork and JCodings\n"
        unless ref($components) eq 'ARRAY' && @$components == 2;
    my @expected = (
        ['org.perlonjava.fork', 'joni-fork', '2.2.7', $FORK_REF, 'MIT'],
        ['org.jruby.jcodings', 'jcodings', '1.0.64', $JCODINGS_REF, 'MIT'],
    );
    for my $wanted (@expected) {
        my ($group, $name, $version, $ref, $license) = @$wanted;
        my @found = grep { ref($_) eq 'HASH'
            && ($_->{group} // '') eq $group && ($_->{name} // '') eq $name }
            @$components;
        die "Notice-license report is missing or duplicates $name\n" unless @found == 1;
        my $component = $found[0];
        die "Notice-license report has wrong $name identity\n"
            unless ($component->{version} // '') eq $version
                && ($component->{bom_ref} // '') eq $ref
                && ($component->{purl} // '') eq $ref
                && ($component->{license} // '') eq $license;
    }
    die "Notice-license report contains legacy Maven Joni identity\n"
        if grep { ref($_) eq 'HASH'
            && (($_->{bom_ref} // '') eq $LEGACY_REF
                || ($_->{purl} // '') eq $LEGACY_REF
                || (($_->{group} // '') eq 'org.jruby.joni'
                    && ($_->{name} // '') eq 'joni')) } @$components;
    my $expected_relations = [
        { from => 'perlonjava', to => $FORK_REF },
        { from => $FORK_REF, to => $JCODINGS_REF },
    ];
    die "Notice-license report relationships are not the exact strict fork contract\n"
        unless canonical($record->{relationships}) eq canonical($expected_relations);
}

sub assert_external_sbom {
    my ($sbom, $expected_commit) = @_;
    die "External SBOM is not CycloneDX\n"
        unless ($sbom->{bomFormat} // '') eq 'CycloneDX';
    my $metadata = require_hash($sbom->{metadata}, 'external SBOM metadata');
    my $root = require_hash($metadata->{component}, 'external SBOM root component');
    die "External SBOM root component is not perlonjava\n"
        unless ($root->{'bom-ref'} // '') eq 'perlonjava';
    my $components = $sbom->{components};
    my $dependencies = $sbom->{dependencies};
    die "External SBOM has no components array\n" unless ref($components) eq 'ARRAY';
    die "External SBOM has no dependencies array\n" unless ref($dependencies) eq 'ARRAY';
    assert_unique_component_ids($components);
    die "External SBOM contains legacy Maven Joni identity\n"
        if grep { ref($_) eq 'HASH'
            && (($_->{'bom-ref'} // '') eq $LEGACY_REF
                || ($_->{purl} // '') eq $LEGACY_REF
                || (($_->{group} // '') eq 'org.jruby.joni'
                    && ($_->{name} // '') eq 'joni')) } @$components;
    my $fork = exact_component($components, 'org.perlonjava.fork', 'joni-fork',
        '2.2.7', $FORK_REF, 'MIT');
    exact_component($components, 'org.jruby.jcodings', 'jcodings',
        '1.0.64', $JCODINGS_REF, 'MIT');
    assert_properties($fork, {
        'perlonjava:vendored' => 'true',
        'perlonjava:modified' => 'true',
        'perlonjava:vendored-source-path' => 'third_party/joni',
        'perlonjava:source-commit' => $expected_commit,
        'perlonjava:upstream-maven-coordinate' => 'org.jruby.joni:joni:2.2.7',
        'perlonjava:upstream-tag' => 'joni-2.2.7',
        'perlonjava:upstream-commit' =>
            '57fd57b4f977813a7b4b35e0179943b1f06f51d7',
    });
    assert_relation($dependencies, 'perlonjava', $FORK_REF, 0,
        'PerlOnJava -> Joni fork');
    assert_relation($dependencies, $FORK_REF, $JCODINGS_REF, 1,
        'Joni fork -> JCodings');
}

sub assert_embedded_sbom {
    my ($jar, $external_bytes) = @_;
    my $entry = 'META-INF/sbom/sbom.json';
    my %entries;
    open my $fh, '-|', 'jar', 'tf', $jar
        or die "Cannot list $jar: $!\n";
    while (my $name = <$fh>) {
        chomp $name;
        $entries{$name}++;
    }
    close $fh or die "Cannot list $jar: jar exited with status $?\n";
    die "Standalone JAR must contain exactly one $entry\n"
        unless ($entries{$entry} // 0) == 1;
    die "Standalone JAR embedded SBOM bytes differ from external SBOM\n"
        unless jar_entry_bytes($jar, $entry) eq $external_bytes;
}

sub exact_component {
    my ($components, $group, $name, $version, $ref, $license) = @_;
    my @found = grep { ref($_) eq 'HASH'
        && ($_->{group} // '') eq $group && ($_->{name} // '') eq $name }
        @$components;
    die "External SBOM is missing or duplicates $name\n" unless @found == 1;
    my $component = $found[0];
    die "External SBOM has wrong $name identity\n"
        unless ($component->{version} // '') eq $version
            && ($component->{'bom-ref'} // '') eq $ref
            && ($component->{purl} // '') eq $ref;
    my @licenses = map { ref($_) eq 'HASH' && ref($_->{license}) eq 'HASH'
        ? ($_->{license}{id} // '') : () } @{$component->{licenses} // []};
    die "External SBOM has wrong $name license\n"
        unless @licenses == 1 && $licenses[0] eq $license;
    return $component;
}

sub assert_unique_component_ids {
    my ($components) = @_;
    my (%refs, %purls);
    for my $component (@$components) {
        die "External SBOM component is not an object\n"
            unless ref($component) eq 'HASH';
        for my $field (['bom-ref', \%refs], ['purl', \%purls]) {
            my ($name, $seen) = @$field;
            next unless defined($component->{$name}) && length($component->{$name});
            die "External SBOM has duplicate $name $component->{$name}\n"
                if $seen->{$component->{$name}}++;
        }
    }
}

sub assert_properties {
    my ($component, $required) = @_;
    my %found;
    my $properties = $component->{properties};
    die "External SBOM Joni fork has no provenance properties\n"
        unless ref($properties) eq 'ARRAY';
    for my $property (@$properties) {
        next unless ref($property) eq 'HASH';
        my $name = $property->{name} // '';
        push @{$found{$name}}, $property->{value} // '' if exists $required->{$name};
    }
    for my $name (sort keys %$required) {
        my $values = $found{$name} // [];
        die "External SBOM Joni fork has missing or duplicate $name property\n"
            unless @$values == 1;
        my $expected = $required->{$name};
        my $matches = ref($expected) eq 'Regexp'
            ? $values->[0] =~ $expected : $values->[0] eq $expected;
        die "External SBOM Joni fork has wrong $name property\n" unless $matches;
    }
}

sub assert_relation {
    my ($dependencies, $from, $to, $exact, $label) = @_;
    my @relations = grep { ref($_) eq 'HASH' && ($_->{ref} // '') eq $from }
        @$dependencies;
    die "External SBOM is missing or duplicates $label relation\n"
        unless @relations == 1;
    my $edges = $relations[0]{dependsOn};
    die "External SBOM $label relation is malformed\n" unless ref($edges) eq 'ARRAY';
    my @matching = grep { defined($_) && !ref($_) && $_ eq $to } @$edges;
    die "External SBOM is missing or duplicates $label edge\n" unless @matching == 1;
    die "External SBOM $label relationship is not exact\n"
        if $exact && (@$edges != 1 || $edges->[0] ne $to);
}

sub jar_entry_bytes {
    my ($jar, $entry) = @_;
    my $directory = tempdir(CLEANUP => 1);
    my $original = getcwd();
    chdir $directory or die "Cannot enter temporary directory: $!\n";
    my $status = system('jar', 'xf', $jar, $entry);
    my $error = $!;
    chdir $original or die "Cannot return to $original: $!\n";
    die "Cannot extract $entry from $jar: $error\n" if $status != 0;
    return read_raw(File::Spec->catfile($directory, split m{/}, $entry));
}

sub seal_evidence {
    my ($path) = @_;
    my $original = absolute_regular_path($path, 'acceptance evidence');
    my $root = abs_path(dirname($original))
        or die "Cannot resolve sealed evidence root\n";
    my $directory = tempdir(CLEANUP => 1);
    my $snapshot_root = File::Spec->catdir($directory, 'evidence');
    make_path($snapshot_root);
    snapshot_tree($root, $snapshot_root);
    my $relative = File::Spec->abs2rel($original, $root);
    my $snapshot_evidence = File::Spec->catfile(
        $snapshot_root, File::Spec->splitdir($relative));
    my $bytes = read_raw($snapshot_evidence);
    return {
        owner => $directory,
        original_evidence => $original,
        original_root => $root,
        snapshot_root => $snapshot_root,
        snapshot_evidence => $snapshot_evidence,
        evidence_bytes => $bytes,
        private => {},
    };
}

sub snapshot_tree {
    my ($root, $destination) = @_;
    find({ no_chdir => 1, follow => 0, wanted => sub {
        my $path = $File::Find::name;
        return if $path eq $root;
        my $relative = File::Spec->abs2rel($path, $root);
        die "Evidence tree traversal escaped its sealed root\n"
            if unsafe_relative($relative);
        my $target = File::Spec->catfile(
            $destination, File::Spec->splitdir($relative));
        my @metadata = lstat($path);
        die "Cannot inspect evidence tree entry $path: $!\n" unless @metadata;
        die "Symlink is forbidden in sealed evidence root: $path\n"
            if S_ISLNK($metadata[2]);
        if (S_ISDIR($metadata[2])) {
            make_path($target);
        } elsif (S_ISREG($metadata[2])) {
            write_snapshot_file($target,
                pin_file_bytes($path, 'sealed evidence file', $root));
        } else {
            die "Non-regular entry is forbidden in sealed evidence root: $path\n";
        }
    }}, $root);
}

sub assert_legacy_artifacts_confined {
    my ($document, $root) = @_;
    my $gates = $document->{gates};
    return unless ref($gates) eq 'HASH';
    for my $gate_id (sort keys %$gates) {
        my $gate = $gates->{$gate_id};
        next unless ref($gate) eq 'HASH';
        assert_artifact_descriptor($gate->{artifact}, $root,
            "$gate_id gate artifact");
        walk_artifact_descriptors($gate->{details}, $root,
            "$gate_id gate details");
    }
}

sub walk_artifact_descriptors {
    my ($value, $root, $label) = @_;
    return unless ref($value);
    if (ref($value) eq 'HASH') {
        for my $key (keys %$value) {
            if ($key eq 'artifact') {
                assert_artifact_descriptor($value->{$key}, $root, "$label artifact");
            } else {
                walk_artifact_descriptors($value->{$key}, $root, "$label $key");
            }
        }
    } elsif (ref($value) eq 'ARRAY') {
        walk_artifact_descriptors($_, $root, $label) for @$value;
    }
}

sub assert_artifact_descriptor {
    my ($descriptor, $root, $label) = @_;
    die "$label descriptor is missing\n" unless ref($descriptor) eq 'HASH';
    my $path = $descriptor->{path};
    die "$label path is missing\n" unless defined($path) && !ref($path) && length($path);
    die "$label is outside the sealed evidence root\n"
        if File::Spec->file_name_is_absolute($path) || unsafe_relative($path);
    resolve_under_root($path, $root, $label);
}

sub unsafe_relative {
    my ($path) = @_;
    return 1 if !defined($path) || ref($path) || File::Spec->file_name_is_absolute($path);
    return scalar grep { $_ eq File::Spec->updir || $_ eq '' }
        File::Spec->splitdir($path);
}

sub snapshot_path {
    my ($sealed, $original, $label) = @_;
    my $relative = File::Spec->abs2rel($original, $sealed->{original_root});
    die "$label is outside the sealed evidence root\n" if unsafe_relative($relative);
    my $snapshot = File::Spec->catfile(
        $sealed->{snapshot_root}, File::Spec->splitdir($relative));
    return existing_file($snapshot, "$label snapshot");
}

sub private_snapshot {
    my ($sealed, $original, $name, $label) = @_;
    my $resolved = absolute_regular_path($original, $label);
    if (path_under_root($resolved, $sealed->{original_root})) {
        return snapshot_path($sealed, $resolved, $label);
    }
    return $sealed->{private}{$resolved} if $sealed->{private}{$resolved};
    my $directory = File::Spec->catdir($sealed->{owner}, 'private');
    make_path($directory);
    my $target = File::Spec->catfile($directory,
        scalar(keys %{$sealed->{private}}) . "-$name");
    write_snapshot_file($target, pin_file_bytes($resolved, $label));
    return $sealed->{private}{$resolved} = $target;
}

sub snapshot_notice_sources {
    my ($sealed, $source_root) = @_;
    my $target_root = File::Spec->catdir($sealed->{owner}, 'notice-source');
    my @relative = (
        ['third_party', 'joni', 'LICENSE'],
        ['third_party', 'joni', 'PERLONJAVA-NOTICE.md'],
        ['third_party', 'licenses', 'jcodings-LICENSE.txt'],
    );
    for my $parts (@relative) {
        my $source = File::Spec->catfile($source_root, @$parts);
        my $target = File::Spec->catfile($target_root, @$parts);
        my $bytes = eval {
            pin_file_bytes($source, 'notice-license source', $source_root) };
        die "Strict notice-license verifier replay rejected the sealed artifacts:\n$@"
            if $@;
        write_snapshot_file($target, $bytes);
    }
    return $target_root;
}

sub path_under_root {
    my ($path, $root) = @_;
    my $relative = File::Spec->abs2rel($path, $root);
    return !unsafe_relative($relative);
}

sub absolute_regular_path {
    my ($path, $label) = @_;
    die "$label path is missing\n" unless defined($path) && !ref($path) && length($path);
    my @metadata = lstat($path);
    die "Cannot inspect $label $path: $!\n" unless @metadata;
    die "$label must not be a symlink: $path\n" if S_ISLNK($metadata[2]);
    die "$label is not a regular nonempty file: $path\n"
        unless S_ISREG($metadata[2]) && $metadata[7] > 0;
    my $resolved = abs_path($path) or die "Cannot resolve $label $path\n";
    return $resolved;
}

sub pin_file_bytes {
    my ($path, $label, $root) = @_;
    my @before = lstat($path);
    die "Cannot inspect $label $path: $!\n" unless @before;
    die "$label must not be a symlink: $path\n" if S_ISLNK($before[2]);
    die "$label is not a regular file: $path\n" unless S_ISREG($before[2]);
    my $flags = O_RDONLY;
    $flags |= Fcntl::O_NOFOLLOW() if defined &Fcntl::O_NOFOLLOW;
    sysopen my $fh, $path, $flags or die "Cannot pin $label $path: $!\n";
    binmode $fh, ':raw' or die "Cannot set raw mode for $label $path: $!\n";
    my @opened = stat($fh);
    die "Cannot stat pinned $label $path: $!\n" unless @opened;
    die "$label identity changed before it was pinned: $path\n"
        unless same_file_identity(\@before, \@opened);
    pin_observer('opened', $path, $label);
    if (defined $root) {
        my $resolved = abs_path($path)
            or die "Cannot resolve pinned $label $path\n";
        die "$label resolved outside the sealed evidence root: $path\n"
            unless path_under_root($resolved, $root);
    }
    my $bytes = '';
    while (1) {
        my $count = sysread($fh, my $chunk, 1024 * 1024);
        die "Cannot read pinned $label $path: $!\n" unless defined $count;
        last unless $count;
        $bytes .= $chunk;
    }
    my @after = stat($fh);
    die "Cannot restat pinned $label $path: $!\n" unless @after;
    close $fh or die "Cannot close pinned $label $path: $!\n";
    pin_observer('before-path-recheck', $path, $label);
    my @final = lstat($path);
    die "$label disappeared while it was pinned: $path\n" unless @final;
    die "$label changed while it was pinned: $path\n"
        unless same_file_identity(\@opened, \@after)
            && same_file_identity(\@opened, \@final)
            && length($bytes) == $opened[7];
    return $bytes;
}

our $PIN_OBSERVER;
sub pin_observer {
    $PIN_OBSERVER->(@_) if $PIN_OBSERVER;
    return;
}

sub same_file_identity {
    my ($left, $right) = @_;
    for my $index (0, 1, 2, 7, 9, 10) {
        return 0 unless $left->[$index] == $right->[$index];
    }
    return 1;
}

sub write_snapshot_file {
    my ($path, $bytes) = @_;
    make_path(dirname($path));
    sysopen my $fh, $path, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot create private snapshot $path: $!\n";
    binmode $fh, ':raw' or die "Cannot set raw snapshot mode $path: $!\n";
    checked_print($fh, $bytes, "private snapshot $path");
    checked_flush($fh, "private snapshot $path");
    checked_close($fh, "private snapshot $path");
}

sub resolve_under_root {
    my ($path, $root, $label) = @_;
    die "$label path is missing\n" unless defined($path) && !ref($path) && length($path);
    die "$label is outside the sealed evidence root\n"
        if File::Spec->file_name_is_absolute($path) || unsafe_relative($path);
    my $candidate = File::Spec->catfile($root, File::Spec->splitdir($path));
    my $resolved = absolute_regular_path($candidate, $label);
    my $relative = File::Spec->abs2rel($resolved, $root);
    die "$label is outside the sealed evidence root\n"
        if File::Spec->file_name_is_absolute($relative)
            || $relative eq File::Spec->updir
            || $relative =~ /\A\.\.(?:[\\\/]|\x00)/;
    return $resolved;
}

sub absolute_report_file {
    my ($path, $label) = @_;
    die "$label path is not absolute\n"
        unless defined($path) && !ref($path) && File::Spec->file_name_is_absolute($path);
    return absolute_regular_path($path, $label);
}

sub existing_file {
    my ($path, $label) = @_;
    my $resolved = abs_path($path) or die "Cannot resolve $label $path\n";
    die "$label is missing or empty: $path\n" unless -f $resolved && -s $resolved;
    return $resolved;
}

sub require_hash {
    my ($value, $label) = @_;
    die "$label must be an object\n" unless ref($value) eq 'HASH';
    return $value;
}

sub require_sha {
    my ($value, $label) = @_;
    die "$label is missing or malformed\n"
        unless defined($value) && !ref($value) && $value =~ /\A[0-9a-f]{64}\z/;
    return $value;
}

sub number {
    my ($value) = @_;
    return defined($value) && !ref($value)
        && $value =~ /\A-?(?:\d+(?:\.\d*)?|\.\d+)\z/;
}

sub true_value {
    my ($value) = @_;
    return defined($value) && ($value eq '1' || $value eq 'true');
}

sub canonical {
    return JSON::PP->new->canonical->encode($_[0]);
}

sub sha256_file {
    return sha256_hex(read_raw($_[0]));
}

sub load_json {
    my ($path, $label) = @_;
    return decode_json_object(read_raw($path), $label, $path);
}

sub decode_json_object {
    my ($bytes, $label, $path) = @_;
    my $document = eval { JSON::PP->new->utf8->decode($bytes) };
    die "Invalid $label JSON in $path\n"
        unless $document && ref($document) eq 'HASH';
    return $document;
}

sub read_raw {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!\n";
    return $contents;
}

sub publish_atomic {
    my ($path, $bytes) = @_;
    die "Refusing to overwrite output $path\n" if -e $path;
    my $absolute = File::Spec->rel2abs($path);
    my $directory = dirname($absolute);
    my $temporary = File::Spec->catfile($directory,
        '.' . (File::Spec->splitpath($absolute))[2] . ".tmp.$$-" . int(rand(1_000_000)));
    my $ready = "$temporary.ready";
    my $fh;
    my $published = 0;
    my $ok = eval {
        sysopen $fh, $temporary, O_WRONLY | O_CREAT | O_EXCL, 0600
            or die "Cannot create temporary output $temporary: $!\n";
        binmode $fh, ':raw' or die "Cannot set raw output mode $temporary: $!\n";
        checked_print($fh, $bytes, $temporary);
        checked_flush($fh, $temporary);
        checked_close($fh, $temporary);
        undef $fh;
        checked_rename($temporary, $ready);
        checked_link($ready, $absolute);
        $published = 1;
        unlink $ready;
        1;
    };
    my $error = $@;
    if (!$ok) {
        CORE::close($fh) if defined $fh;
        unlink($temporary) if -e $temporary;
        unlink($ready) if -e $ready;
        die $error;
    }
    return $published;
}

sub checked_print {
    my ($fh, $bytes, $label) = @_;
    print {$fh} $bytes or die "Cannot write $label: $!\n";
}

sub checked_flush {
    my ($fh, $label) = @_;
    $fh->flush or die "Cannot flush $label: $!\n";
}

sub checked_close {
    my ($fh, $label) = @_;
    close $fh or die "Cannot close $label: $!\n";
}

sub checked_rename {
    my ($from, $to) = @_;
    rename $from, $to or die "Cannot atomically publish $to: $!\n";
}

sub checked_link {
    my ($from, $to) = @_;
    link $from, $to or die "Cannot atomically publish $to without overwrite: $!\n";
}

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: check_phase36_release_manifest.pl --evidence FILE
       --expected-commit FULL_SHA [--output FILE]

Final, fail-closed Phase 36 release wrapper. It first requires the existing
acceptance checker to pass in strict authoritative mode with the checked-in
requirements, then independently verifies the sealed strict Joni fork notice,
provenance, external SBOM, and byte-identical embedded SBOM artifact.
USAGE
    exit $status;
}

1;
