#!/usr/bin/env perl

use strict;
use warnings;

use Cwd qw(abs_path getcwd);
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname);
use File::Spec;
use File::Temp qw(tempdir);
use Getopt::Long qw(GetOptionsFromArray);
use JSON::PP;

my $FORK_REF = 'pkg:generic/perlonjava/joni-fork@2.2.7';
my $LEGACY_REF = 'pkg:maven/org.jruby.joni/joni@2.2.7?type=jar';
my $JCODINGS_REF = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';
my $TOOL_DIR = dirname(abs_path(__FILE__))
    or die "Cannot resolve release wrapper directory\n";

exit main(@ARGV) unless caller;

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

    my $evidence_path = existing_file($evidence, 'acceptance evidence');
    my $evidence_sha256 = sha256_file($evidence_path);
    my $legacy = run_legacy_checker($evidence_path, $expected_commit);
    die "Legacy acceptance checker did not produce an authoritative strict report\n"
        unless true_value($legacy->{summary}{authoritative})
            && ($legacy->{check_mode} // '') eq 'strict'
            && ($legacy->{expected_commit} // '') eq $expected_commit;

    my $document = load_json($evidence_path, 'acceptance evidence');
    my $strict = verify_strict_notice_artifact(
        $evidence_path, $document, $expected_commit);
    die "Acceptance evidence mutated during final release verification\n"
        unless sha256_file($evidence_path) eq $evidence_sha256;

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
        write_exclusive($output, $rendered);
    } else {
        print $rendered;
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
    my ($evidence_path, $evidence, $expected_commit) = @_;
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
    my $root = abs_path(dirname($evidence_path))
        or die "Cannot resolve sealed evidence root\n";
    my $artifact_path = resolve_under_root($artifact->{path}, $root,
        'notice-license artifact');
    die "Notice-license artifact hash mismatch\n"
        unless sha256_file($artifact_path) eq $artifact_sha;
    my $record = load_json($artifact_path, 'strict notice-license artifact');
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
    die "Standalone JAR hash differs from sealed identity\n"
        unless sha256_file($jar) eq $sealed_jar_sha;
    die "External SBOM hash differs from sealed identity\n"
        unless sha256_file($sbom_file) eq $sealed_sbom_sha;

    assert_report_contract($record);
    assert_strict_verifier_replay(
        $record, $artifact_path, $jar, $sbom_file, $expected_commit);
    my $sbom_bytes = read_raw($sbom_file);
    assert_embedded_sbom($jar, $sbom_bytes);

    die "Notice-license artifact mutated during final release verification\n"
        unless sha256_file($artifact_path) eq $artifact_sha;
    die "Standalone JAR mutated during final release verification\n"
        unless sha256_file($jar) eq $sealed_jar_sha;
    die "External SBOM mutated during final release verification\n"
        unless sha256_file($sbom_file) eq $sealed_sbom_sha;

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
    my ($record, $artifact, $jar, $sbom, $expected_commit) = @_;
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
    my $verifier = File::Spec->catfile($TOOL_DIR,
        'verify_phase36_notice_license.pl');
    existing_file($verifier, 'strict notice-license verifier');
    my $directory = tempdir(CLEANUP => 1);
    my $replayed = File::Spec->catfile($directory, 'notice-license.json');
    my ($status, $text) = capture_command($^X, $verifier, '--strict',
        '--source-root', $source_root, '--jar', $jar, '--sbom', $sbom,
        '--output', $replayed);
    die "Strict notice-license verifier replay rejected the sealed artifacts:\n$text"
        if $status != 0;
    die "Sealed notice-license artifact is not byte-identical strict verifier output\n"
        unless read_raw($replayed) eq read_raw($artifact);
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

sub resolve_under_root {
    my ($path, $root, $label) = @_;
    die "$label path is missing\n" unless defined($path) && !ref($path) && length($path);
    my $candidate = File::Spec->file_name_is_absolute($path)
        ? $path : File::Spec->catfile($root, File::Spec->splitdir($path));
    my $resolved = existing_file($candidate, $label);
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
    return existing_file($path, $label);
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

sub write_exclusive {
    my ($path, $bytes) = @_;
    require Fcntl;
    sysopen my $fh, $path,
        Fcntl::O_WRONLY() | Fcntl::O_CREAT() | Fcntl::O_EXCL(), 0600
        or die "Cannot exclusively create $path: $!\n";
    binmode $fh, ':raw';
    print {$fh} $bytes or die "Cannot write $path: $!\n";
    close $fh or die "Cannot close $path: $!\n";
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
