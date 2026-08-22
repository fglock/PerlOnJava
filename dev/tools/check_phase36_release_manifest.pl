#!/usr/bin/env perl

use strict;
use warnings;

use Cwd qw(abs_path getcwd);
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Fcntl qw(:DEFAULT :mode);
use Getopt::Long qw(GetOptionsFromArray);
use IO::Handle;
use JSON::PP;
use Time::HiRes ();

my $FORK_REF = 'pkg:generic/perlonjava/joni-fork@2.2.7';
my $LEGACY_REF = 'pkg:maven/org.jruby.joni/joni@2.2.7?type=jar';
my $JCODINGS_REF = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';
my $TOOL_DIR = dirname(abs_path(__FILE__))
    or die "Cannot resolve release wrapper directory\n";
my $STREAM_BUFFER = 1024 * 1024;
my $MAX_JSON_BYTES = 64 * 1024 * 1024;
my $MAX_CHILD_OUTPUT = 1024 * 1024;
my $MAX_JAR_ENTRIES = 200_000;

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
    my $evidence_sha256 = $sealed->{evidence_sha256};
    my $document = decode_json_object($sealed->{evidence_bytes},
        'acceptance evidence', $evidence_path);
    assert_legacy_artifacts_confined($document, $sealed->{original_root});
    pin_validation_inputs($sealed);
    my $legacy = run_legacy_checker(
        $sealed->{snapshot_evidence}, $expected_commit, $sealed);
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
    my ($evidence, $expected_commit, $sealed) = @_;
    $sealed //= seal_evidence($evidence);
    pin_validation_inputs($sealed) unless $sealed->{inputs};
    my $checker = $sealed->{inputs}{legacy}{snapshot};
    my $requirements = $sealed->{inputs}{requirements}{snapshot};
    assert_pinned_input($sealed->{inputs}{legacy});
    assert_pinned_input($sealed->{inputs}{requirements});
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
    assert_pinned_input($sealed->{inputs}{legacy});
    assert_pinned_input($sealed->{inputs}{requirements});
    return load_json_bounded($report, 'legacy strict acceptance report');
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
        unless sha256_file_streaming($artifact_snapshot) eq $artifact_sha;
    my $record = load_json_bounded(
        $artifact_snapshot, 'strict notice-license artifact');
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
        unless sha256_file_streaming($jar_snapshot) eq $sealed_jar_sha;
    die "External SBOM hash differs from sealed identity\n"
        unless sha256_file_streaming($sbom_snapshot) eq $sealed_sbom_sha;

    assert_report_contract($record);
    assert_strict_verifier_replay(
        $record, $artifact_snapshot, $jar_snapshot, $sbom_snapshot,
        $expected_commit, $sealed);
    assert_embedded_sbom($jar_snapshot, $sbom_snapshot, $sealed);

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
    assert_sbom_expected_commit_streaming($sbom, $expected_commit);
    my $source_root = $record->{source_root};
    die "Notice-license report source_root is not absolute\n"
        unless defined($source_root) && !ref($source_root)
            && File::Spec->file_name_is_absolute($source_root);
    my $reported_source_root = $source_root;
    $source_root = abs_path($source_root)
        or die "Cannot resolve notice-license report source_root\n";
    die "Notice-license report source_root is not canonical; "
        . "Strict notice-license verifier replay rejected the sealed artifacts\n"
        unless $reported_source_root eq $source_root;
    die "Notice-license report source_root is not a directory\n" unless -d $source_root;
    my $source_snapshot = snapshot_notice_sources($sealed, $source_root);
    pin_validation_inputs($sealed) unless $sealed->{inputs};
    my $verifier = $sealed->{inputs}{verifier}{snapshot};
    assert_pinned_input($sealed->{inputs}{verifier});
    assert_pinned_input($sealed->{inputs}{jar});
    my $directory = tempdir(CLEANUP => 1);
    my $replayed = File::Spec->catfile($directory, 'notice-license.json');
    my ($status, $text);
    {
        local $ENV{PATH} = dirname($sealed->{inputs}{jar}{snapshot});
        ($status, $text) = capture_command($^X, $verifier, '--strict',
            '--source-root', $source_snapshot, '--jar', $jar, '--sbom', $sbom,
            '--output', $replayed);
    }
    die "Strict notice-license verifier replay rejected the sealed artifacts:\n$text"
        if $status != 0;
    assert_pinned_input($sealed->{inputs}{verifier});
    assert_pinned_input($sealed->{inputs}{jar});
    my $replay = load_json_bounded(
        $replayed, 'replayed notice-license artifact');
    my $translated = translated_replay_record(
        $record, $jar, $sbom, $source_root, $source_snapshot);
    die "Sealed notice-license artifact differs from strict verifier replay\n"
        unless canonical($replay) eq canonical($translated);
}

sub translated_replay_record {
    my ($record, $jar, $sbom, $source_root, $source_snapshot) = @_;
    my $copy = JSON::PP->new->decode(canonical($record));
    for my $field ([jar_path => $jar], [sbom_path => $sbom]) {
        my ($name, $snapshot) = @$field;
        $copy->{$name} = abs_path($snapshot)
            or die "Cannot canonicalize derived $name snapshot\n";
    }
    my $canonical_source_snapshot = abs_path($source_snapshot)
        or die "Cannot canonicalize derived notice source snapshot\n";
    $copy->{source_root} = $canonical_source_snapshot;
    my %notice_path = (
        'joni-license' => ['third_party', 'joni', 'LICENSE'],
        'joni-notice' => ['third_party', 'joni', 'PERLONJAVA-NOTICE.md'],
        'jcodings-license' => ['third_party', 'licenses', 'jcodings-LICENSE.txt'],
    );
    die "Notice-license report notices must contain exactly three records\n"
        unless ref($copy->{notices}) eq 'ARRAY' && @{$copy->{notices}} == 3;
    my %seen;
    for my $notice (@{$copy->{notices}}) {
        die "Notice-license report notice is malformed\n"
            unless ref($notice) eq 'HASH' && exists $notice_path{$notice->{id} // ''}
                && !$seen{$notice->{id}}++;
        my $parts = $notice_path{$notice->{id}};
        my $canonical = File::Spec->catfile($source_root, @$parts);
        die "Notice-license report notice path is not the sealed source path\n"
            unless ($notice->{path} // '') eq $canonical;
        $notice->{path} = File::Spec->catfile($canonical_source_snapshot, @$parts);
    }
    return $copy;
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
    my $text = '';
    while (1) {
        my $count = sysread($read, my $chunk, 64 * 1024);
        die "Cannot read verifier output: $!\n" unless defined $count;
        last unless $count;
        $text .= $chunk;
        if (length($text) > $MAX_CHILD_OUTPUT) {
            kill 'KILL', $pid;
            waitpid($pid, 0);
            die "Strict verifier output exceeded the bounded capture limit\n";
        }
    }
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

sub assert_sbom_expected_commit_streaming {
    my ($path, $expected_commit) = @_;
    sysopen my $fh, $path, O_RDONLY or die "Cannot stream external SBOM $path: $!\n";
    binmode $fh, ':raw' or die "Cannot set raw SBOM stream mode: $!\n";
    my $stream = { fh => $fh, buffer => '', offset => 0, eof => 0,
        fork_count => 0, commit_count => 0, commit_value => undef };
    json_stream_value($stream, [], 0);
    json_stream_space($stream);
    die "External SBOM contains trailing JSON data\n"
        if defined json_stream_peek($stream);
    close $fh or die "Cannot close streamed external SBOM $path: $!\n";
    die "External SBOM is not CycloneDX\n"
        unless ($stream->{bom_format} // '') eq 'CycloneDX';
    die "External SBOM is missing or duplicates the Joni fork component\n"
        unless $stream->{fork_count} == 1;
    die "External SBOM Joni fork has missing or duplicate perlonjava:source-commit property\n"
        unless $stream->{commit_count} == 1;
    die "External SBOM Joni fork has wrong perlonjava:source-commit property\n"
        unless ($stream->{commit_value} // '') eq $expected_commit;
    die "External SBOM Joni fork -> JCodings relationship is not exact\n"
        unless ($stream->{fork_relation_count} // 0) == 1
            && $stream->{fork_relation_exact};
}

sub json_stream_value {
    my ($stream, $path, $depth) = @_;
    die "External SBOM JSON nesting exceeds bound\n" if $depth > 256;
    json_stream_space($stream);
    my $next = json_stream_peek($stream);
    die "Unexpected end of external SBOM JSON\n" unless defined $next;
    return json_stream_object($stream, $path, $depth + 1) if $next eq '{';
    return json_stream_array($stream, $path, $depth + 1) if $next eq '[';
    return json_stream_string($stream) if $next eq '"';
    my $token = '';
    while (defined($next = json_stream_peek($stream))
            && $next !~ /[\s,\]\}]/) {
        $token .= json_stream_get($stream);
        die "External SBOM scalar token exceeds bound\n" if length($token) > 1024;
    }
    die "Malformed external SBOM scalar\n"
        unless $token =~ /\A(?:null|true|false|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)\z/;
    return $token;
}

sub json_stream_object {
    my ($stream, $path, $depth) = @_;
    json_stream_expect($stream, '{');
    my %selected;
    json_stream_space($stream);
    unless ((json_stream_peek($stream) // '') eq '}') {
        while (1) {
            die "External SBOM object key is not a string\n"
                unless (json_stream_peek($stream) // '') eq '"';
            my $key = json_stream_string($stream);
            json_stream_space($stream);
            json_stream_expect($stream, ':');
            my $value = json_stream_value($stream, [@$path, $key], $depth);
            if (object_is_component($path) && $key eq 'bom-ref' && !ref($value)) {
                $selected{bom_ref} = $value;
            } elsif (!@$path && $key eq 'bomFormat' && !ref($value)) {
                $stream->{bom_format} = $value;
            } elsif (object_is_property($path) && ($key eq 'name' || $key eq 'value')
                    && !ref($value)) {
                $selected{$key} = $value;
            } elsif (object_is_component($path) && $key eq 'properties'
                    && ref($value) eq 'HASH') {
                $selected{properties} = $value;
            } elsif (object_is_dependency($path) && $key eq 'ref' && !ref($value)) {
                $selected{ref} = $value;
            } elsif (object_is_dependency($path) && $key eq 'dependsOn'
                    && ref($value) eq 'HASH') {
                $selected{depends} = $value;
            }
            json_stream_space($stream);
            last if (json_stream_peek($stream) // '') eq '}';
            json_stream_expect($stream, ',');
            json_stream_space($stream);
        }
    }
    json_stream_expect($stream, '}');
    if (object_is_property($path)) {
        return { property_matches => (($selected{name} // '')
                eq 'perlonjava:source-commit') ? 1 : 0,
            property_value => $selected{value} };
    }
    if (object_is_component($path)
            && ($selected{bom_ref} // '') eq $FORK_REF) {
        $stream->{fork_count}++;
        my $properties = $selected{properties} // {};
        $stream->{commit_count} += $properties->{matches} // 0;
        $stream->{commit_value} = $properties->{value}
            if ($properties->{matches} // 0) == 1;
    }
    if (object_is_dependency($path) && ($selected{ref} // '') eq $FORK_REF) {
        $stream->{fork_relation_count}++;
        my $depends = $selected{depends} // {};
        $stream->{fork_relation_exact} = ($depends->{count} // 0) == 1
            && ($depends->{matching} // 0) == 1 && !($depends->{unexpected} // 0);
    }
    return {};
}

sub json_stream_array {
    my ($stream, $path, $depth) = @_;
    json_stream_expect($stream, '[');
    my ($index, $matches, $value, $matching, $unexpected) = (0, 0, undef, 0, 0);
    json_stream_space($stream);
    unless ((json_stream_peek($stream) // '') eq ']') {
        while (1) {
            my $item = json_stream_value($stream, [@$path, $index++], $depth);
            if (array_is_properties($path) && ref($item) eq 'HASH'
                    && $item->{property_matches}) {
                $matches++;
                $value = $item->{property_value};
            } elsif (array_is_depends_on($path)) {
                $matching++ if !ref($item) && $item eq $JCODINGS_REF;
                $unexpected++ if ref($item) || $item ne $JCODINGS_REF;
            }
            json_stream_space($stream);
            last if (json_stream_peek($stream) // '') eq ']';
            json_stream_expect($stream, ',');
            json_stream_space($stream);
        }
    }
    json_stream_expect($stream, ']');
    return { matches => $matches, value => $value } if array_is_properties($path);
    return { count => $index, matching => $matching, unexpected => $unexpected }
        if array_is_depends_on($path);
    return [];
}

sub object_is_component {
    my ($path) = @_;
    return @$path == 2 && $path->[0] eq 'components' && $path->[1] =~ /\A\d+\z/;
}

sub object_is_property {
    my ($path) = @_;
    return @$path == 4 && $path->[0] eq 'components'
        && $path->[1] =~ /\A\d+\z/ && $path->[2] eq 'properties'
        && $path->[3] =~ /\A\d+\z/;
}

sub object_is_dependency {
    my ($path) = @_;
    return @$path == 2 && $path->[0] eq 'dependencies' && $path->[1] =~ /\A\d+\z/;
}

sub array_is_properties {
    my ($path) = @_;
    return @$path == 3 && $path->[0] eq 'components'
        && $path->[1] =~ /\A\d+\z/ && $path->[2] eq 'properties';
}

sub array_is_depends_on {
    my ($path) = @_;
    return @$path == 3 && $path->[0] eq 'dependencies'
        && $path->[1] =~ /\A\d+\z/ && $path->[2] eq 'dependsOn';
}

sub json_stream_space {
    my ($stream) = @_;
    json_stream_get($stream) while defined(json_stream_peek($stream))
        && json_stream_peek($stream) =~ /\s/;
}

sub json_stream_string {
    my ($stream) = @_;
    my $raw = json_stream_get($stream);
    die "External SBOM string did not start with a quote\n" unless $raw eq '"';
    my $escaped = 0;
    while (1) {
        my $char = json_stream_get($stream);
        die "Unterminated external SBOM string\n" unless defined $char;
        $raw .= $char;
        die "External SBOM string exceeds bound\n" if length($raw) > 1024 * 1024;
        if (!$escaped && $char eq '"') { last }
        if (!$escaped && $char eq '\\') { $escaped = 1 }
        else { $escaped = 0 }
    }
    my $decoded = eval { JSON::PP->new->utf8->decode($raw) };
    die "Malformed external SBOM JSON string\n" if $@ || ref($decoded);
    return $decoded;
}

sub json_stream_expect {
    my ($stream, $wanted) = @_;
    my $found = json_stream_get($stream);
    die "Malformed external SBOM JSON: expected $wanted\n"
        unless defined($found) && $found eq $wanted;
}

sub json_stream_peek {
    my ($stream) = @_;
    json_stream_fill($stream) unless $stream->{offset} < length($stream->{buffer});
    return undef if $stream->{offset} >= length($stream->{buffer});
    return substr($stream->{buffer}, $stream->{offset}, 1);
}

sub json_stream_get {
    my ($stream) = @_;
    my $char = json_stream_peek($stream);
    $stream->{offset}++ if defined $char;
    return $char;
}

sub json_stream_fill {
    my ($stream) = @_;
    return if $stream->{eof};
    my $count = sysread($stream->{fh}, my $chunk, 64 * 1024);
    die "Cannot stream external SBOM: $!\n" unless defined $count;
    if (!$count) {
        $stream->{eof} = 1;
        $stream->{buffer} = '';
        $stream->{offset} = 0;
        return;
    }
    $stream->{buffer} = $chunk;
    $stream->{offset} = 0;
}

sub assert_embedded_sbom {
    my ($jar, $external_file, $sealed) = @_;
    my $entry = 'META-INF/sbom/sbom.json';
    my %entries;
    assert_pinned_input($sealed->{inputs}{jar});
    {
        local $ENV{PATH} = dirname($sealed->{inputs}{jar}{snapshot});
        open my $fh, '-|', 'jar', 'tf', $jar
            or die "Cannot list $jar: $!\n";
        my $count = 0;
        while (my $name = <$fh>) {
            die "JAR entry inventory line exceeds bound\n" if length($name) > 8192;
            die "JAR entry inventory exceeds bound\n" if ++$count > $MAX_JAR_ENTRIES;
            chomp $name;
            $entries{$name}++;
        }
        close $fh or die "Cannot list $jar: jar exited with status $?\n";
    }
    die "Standalone JAR must contain exactly one $entry\n"
        unless ($entries{$entry} // 0) == 1;
    my $embedded = jar_entry_file($jar, $entry, $sealed);
    die "Standalone JAR embedded SBOM bytes differ from external SBOM\n"
        unless files_equal_streaming($embedded, $external_file);
    assert_pinned_input($sealed->{inputs}{jar});
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

sub jar_entry_file {
    my ($jar, $entry, $sealed) = @_;
    my $directory = tempdir(DIR => $sealed->{owner}, CLEANUP => 0);
    my $original = getcwd();
    chdir $directory or die "Cannot enter temporary directory: $!\n";
    local $ENV{PATH} = dirname($sealed->{inputs}{jar}{snapshot});
    my $status = system('jar', 'xf', $jar, $entry);
    my $error = $!;
    chdir $original or die "Cannot return to $original: $!\n";
    die "Cannot extract $entry from $jar: $error\n" if $status != 0;
    return existing_file(
        File::Spec->catfile($directory, split m{/}, $entry),
        'extracted embedded SBOM');
}

sub seal_evidence {
    my ($path) = @_;
    my $original = absolute_regular_path($path, 'acceptance evidence');
    my $root = abs_path(dirname($original))
        or die "Cannot resolve sealed evidence root\n";
    my $directory = tempdir(CLEANUP => 1);
    my $snapshot_root = File::Spec->catdir($directory, 'evidence');
    make_path($snapshot_root);
    my $relative = File::Spec->abs2rel($original, $root);
    my $snapshot_evidence = File::Spec->catfile(
        $snapshot_root, File::Spec->splitdir($relative));
    my $evidence_copy = stream_snapshot_file(
        $original, $snapshot_evidence, 'acceptance evidence');
    die "Acceptance evidence JSON exceeds bounded metadata limit\n"
        if $evidence_copy->{size} > $MAX_JSON_BYTES;
    my $bytes = read_raw_bounded($snapshot_evidence, $MAX_JSON_BYTES);
    my $document = decode_json_object($bytes, 'acceptance evidence', $original);
    my $sealed = {
        owner => $directory,
        original_evidence => $original,
        original_root => $root,
        snapshot_root => $snapshot_root,
        snapshot_evidence => $snapshot_evidence,
        evidence_sha256 => $evidence_copy->{sha256},
        evidence_bytes => $bytes,
        snapshots => { $original => $evidence_copy },
        private => {}, copied_bytes => $evidence_copy->{size}, copied_files => 1,
    };
    my @queue;
    my $gates = ref($document->{gates}) eq 'HASH' ? $document->{gates} : {};
    for my $gate_id (sort keys %$gates) {
        my $gate = require_hash($gates->{$gate_id}, "$gate_id gate");
        enqueue_descriptor(\@queue, $gate->{artifact}, $root, $root,
            "$gate_id gate artifact", 1);
        discover_descriptors($gate->{details}, $root, $root,
            "$gate_id gate details", \@queue);
    }
    my %processed;
    while (my $item = shift @queue) {
        my $source = descriptor_source($item->{descriptor}{path},
            $item->{base}, $root, $item->{label});
        my $expected = $item->{descriptor}{sha256};
        if (my $prior = $processed{$source}) {
            die "$item->{label} has conflicting SHA-256 descriptors\n"
                unless $prior eq $expected;
            next;
        }
        $processed{$source} = $expected;
        my $rel = File::Spec->abs2rel($source, $root);
        my $target = File::Spec->catfile(
            $snapshot_root, File::Spec->splitdir($rel));
        my $copy = stream_snapshot_file(
            $source, $target, $item->{label}, $expected, $root);
        $sealed->{snapshots}{$source} = $copy;
        $sealed->{copied_bytes} += $copy->{size};
        $sealed->{copied_files}++;
        if ($source =~ /\.json\z/i) {
            die "$item->{label} JSON exceeds bounded descriptor limit\n"
                if $copy->{size} > $MAX_JSON_BYTES;
            my $nested = decode_json_value(
                read_raw_bounded($target, $MAX_JSON_BYTES), $item->{label}, $source);
            discover_descriptors($nested, dirname($source), $root,
                "$item->{label} document", \@queue);
        }
    }
    return $sealed;
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
    my @found;
    discover_descriptors($value, $root, $root, $label, \@found);
}

sub assert_artifact_descriptor {
    my ($descriptor, $root, $label) = @_;
    my @queue;
    enqueue_descriptor(\@queue, $descriptor, $root, $root, $label, 1);
    descriptor_source($descriptor->{path}, $root, $root, $label);
}

sub descriptor_shape {
    my ($value) = @_;
    return descriptor_candidate($value)
        && defined($value->{path}) && !ref($value->{path}) && length($value->{path})
        && !unsafe_relative($value->{path})
        && defined($value->{sha256}) && !ref($value->{sha256})
        && $value->{sha256} =~ /\A[0-9a-f]{64}\z/;
}

sub descriptor_candidate {
    my ($value) = @_;
    return 0 unless ref($value) eq 'HASH'
        && exists($value->{path}) && exists($value->{sha256})
        && defined($value->{path}) && !ref($value->{path})
        && defined($value->{sha256}) && !ref($value->{sha256})
        && $value->{sha256} =~ /\A[0-9a-f]{64}\z/;
    my %permitted = map { $_ => 1 } qw(path sha256 kind size bytes media_type);
    return !(grep { !$permitted{$_} } keys %$value);
}

sub enqueue_descriptor {
    my ($queue, $descriptor, $base, $root, $label, $required) = @_;
    if (!descriptor_shape($descriptor)) {
        if ($required && descriptor_candidate($descriptor)
                && defined($descriptor->{path}) && !ref($descriptor->{path})
                && unsafe_relative($descriptor->{path})) {
            die "$label is outside the sealed evidence root\n";
        }
        die "$label descriptor is missing or malformed\n" if $required;
        return;
    }
    descriptor_source($descriptor->{path}, $base, $root, $label);
    push @$queue, { descriptor => $descriptor, base => $base, label => $label };
}

sub discover_descriptors {
    my ($value, $base, $root, $label, $queue) = @_;
    return unless ref($value);
    if (descriptor_shape($value)) {
        enqueue_descriptor($queue, $value, $base, $root, $label, 0);
        return;
    }
    if (descriptor_candidate($value)) {
        enqueue_descriptor($queue, $value, $base, $root, $label, 1);
        return;
    }
    if (ref($value) eq 'HASH') {
        discover_descriptors($value->{$_}, $base, $root, "$label $_", $queue)
            for sort keys %$value;
    } elsif (ref($value) eq 'ARRAY') {
        for my $index (0 .. $#$value) {
            discover_descriptors($value->[$index], $base, $root,
                "$label item $index", $queue);
        }
    }
}

sub descriptor_source {
    my ($path, $base, $root, $label) = @_;
    die "$label path is outside the sealed evidence root\n"
        if unsafe_relative($path);
    my $candidate = File::Spec->canonpath(
        File::Spec->catfile($base, File::Spec->splitdir($path)));
    my $relative = File::Spec->abs2rel($candidate, $root);
    die "$label is outside the sealed evidence root\n" if unsafe_relative($relative);
    my $cursor = $root;
    my @parts = File::Spec->splitdir($relative);
    for my $index (0 .. $#parts) {
        $cursor = File::Spec->catfile($cursor, $parts[$index]);
        my @st = Time::HiRes::lstat($cursor);
        die "Cannot inspect $label $cursor: $!\n" unless @st;
        die "$label must not be a symlink or traverse one: $cursor\n"
            if S_ISLNK($st[2]);
        die "$label has a non-directory path component: $cursor\n"
            if $index < $#parts && !S_ISDIR($st[2]);
    }
    return $candidate;
}

sub unsafe_relative {
    my ($path) = @_;
    return 1 if !defined($path) || ref($path) || File::Spec->file_name_is_absolute($path);
    return scalar grep { $_ eq File::Spec->updir || $_ eq '' }
        File::Spec->splitdir($path);
}

sub snapshot_path {
    my ($sealed, $original, $label) = @_;
    my $record = $sealed->{snapshots}{$original}
        or die "$label was not included in the descriptor-driven snapshot\n";
    assert_snapshot_record($record, "$label snapshot");
    return $record->{snapshot};
}

sub private_snapshot {
    my ($sealed, $original, $name, $label) = @_;
    my $resolved = absolute_regular_path($original, $label);
    if (path_under_root($resolved, $sealed->{original_root})
            && $sealed->{snapshots}{$resolved}) {
        return snapshot_path($sealed, $resolved, $label);
    }
    if (my $record = $sealed->{private}{$resolved}) {
        assert_snapshot_record($record, $label);
        return $record->{snapshot};
    }
    my $directory = File::Spec->catdir($sealed->{owner}, 'private');
    make_path($directory);
    my $target = File::Spec->catfile($directory,
        scalar(keys %{$sealed->{private}}) . "-$name");
    my $record = stream_snapshot_file($resolved, $target, $label);
    $sealed->{copied_bytes} += $record->{size};
    $sealed->{copied_files}++;
    $sealed->{private}{$resolved} = $record;
    return $record->{snapshot};
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
        my $record = eval { stream_snapshot_file(
            $source, $target, 'notice-license source', undef, $source_root) };
        die "Strict notice-license verifier replay rejected the sealed artifacts:\n$@" if $@;
        $sealed->{copied_bytes} += $record->{size};
        $sealed->{copied_files}++;
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
    my @metadata = Time::HiRes::lstat($path);
    die "Cannot inspect $label $path: $!\n" unless @metadata;
    die "$label must not be a symlink: $path\n" if S_ISLNK($metadata[2]);
    die "$label is not a regular nonempty file: $path\n"
        unless S_ISREG($metadata[2]) && $metadata[7] > 0;
    my $resolved = abs_path($path) or die "Cannot resolve $label $path\n";
    return $resolved;
}

sub stream_snapshot_file {
    my ($path, $target, $label, $expected_sha, $root) = @_;
    my @before = Time::HiRes::lstat($path);
    die "Cannot inspect $label $path: $!\n" unless @before;
    die "$label must not be a symlink: $path\n" if S_ISLNK($before[2]);
    die "$label is not a regular file: $path\n" unless S_ISREG($before[2]);
    my $flags = O_RDONLY;
    $flags |= Fcntl::O_NOFOLLOW() if defined &Fcntl::O_NOFOLLOW;
    sysopen my $fh, $path, $flags or die "Cannot pin $label $path: $!\n";
    binmode $fh, ':raw' or die "Cannot set raw mode for $label $path: $!\n";
    my @opened = Time::HiRes::stat($fh);
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
    make_path(dirname($target));
    sysopen my $out, $target, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot create private snapshot $target: $!\n";
    binmode $out, ':raw' or die "Cannot set raw snapshot mode $target: $!\n";
    my $digest = Digest::SHA->new(256);
    my $total = 0;
    my $ok = eval {
        while (1) {
            my $count = sysread($fh, my $chunk, $STREAM_BUFFER);
            die "Cannot read pinned $label $path: $!\n" unless defined $count;
            last unless $count;
            $digest->add($chunk);
            $total += $count;
            my $offset = 0;
            while ($offset < $count) {
                my $written = syswrite($out, $chunk, $count - $offset, $offset);
                die "Cannot write private snapshot $target: $!\n"
                    unless defined($written) && $written > 0;
                $offset += $written;
            }
        }
        checked_flush($out, "private snapshot $target");
        checked_close($out, "private snapshot $target");
        undef $out;
        1;
    };
    my $copy_error = $@;
    if (!$ok) {
        CORE::close($out) if defined $out;
        unlink $target;
        die $copy_error;
    }
    my @after = Time::HiRes::stat($fh);
    die "Cannot restat pinned $label $path: $!\n" unless @after;
    pin_observer('before-path-recheck', $path, $label);
    my @final = Time::HiRes::lstat($path);
    die "$label disappeared while it was pinned: $path\n" unless @final;
    my $sha = $digest->hexdigest;
    die "$label changed while it was pinned: $path\n"
        unless same_file_identity(\@opened, \@after)
            && same_file_identity(\@opened, \@final)
            && $total == $opened[7];
    close $fh or die "Cannot close pinned $label $path: $!\n";
    if (defined $expected_sha && $sha ne $expected_sha) {
        unlink $target;
        die "$label hash mismatch\n";
    }
    chmod 0400, $target or die "Cannot make snapshot immutable $target: $!\n";
    my @snapshot_identity = Time::HiRes::lstat($target);
    return { source => $path, snapshot => $target, sha256 => $sha,
        size => $total, identity => \@snapshot_identity,
        source_identity => \@final };
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

sub assert_snapshot_record {
    my ($record, $label) = @_;
    my @now = Time::HiRes::lstat($record->{snapshot});
    die "$label identity changed\n"
        unless @now && !S_ISLNK($now[2]) && S_ISREG($now[2])
            && same_file_identity($record->{identity}, \@now)
            && sha256_file_streaming($record->{snapshot}) eq $record->{sha256};
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
    my $resolved = absolute_regular_path($path, $label);
    die "$label path is not canonical\n" unless $path eq $resolved;
    return $resolved;
}

sub pin_validation_inputs {
    my ($sealed) = @_;
    return $sealed->{inputs} if $sealed->{inputs};
    my $root = File::Spec->catdir($sealed->{owner}, 'pinned-inputs');
    my $bin = File::Spec->catdir($root, 'bin');
    make_path($bin);
    my @inputs = (
        [legacy => File::Spec->catfile($TOOL_DIR,
            'check_phase36_acceptance_manifest.pl'), 'legacy acceptance checker',
            File::Spec->catfile($root, 'legacy-checker.pl'), 0500],
        [requirements => File::Spec->catfile($TOOL_DIR,
            'phase36_acceptance_requirements.json'), 'acceptance requirements',
            File::Spec->catfile($root, 'requirements.json'), 0400],
        [verifier => File::Spec->catfile($TOOL_DIR,
            'verify_phase36_notice_license.pl'), 'strict notice-license verifier',
            File::Spec->catfile($root, 'notice-verifier.pl'), 0500],
        [jar => resolve_executable('jar'), 'jar executable',
            File::Spec->catfile($root, 'jar.identity'), 0400],
    );
    my %pinned;
    for my $input (@inputs) {
        my ($name, $source, $label, $target, $mode) = @$input;
        $source = absolute_regular_path($source, $label);
        my $record = stream_snapshot_file($source, $target, $label);
        chmod $mode, $target or die "Cannot set pinned $label mode: $!\n";
        $record->{identity} = [Time::HiRes::lstat($target)];
        $record->{label} = $label;
        $pinned{$name} = $record;
        $sealed->{copied_bytes} += $record->{size};
        $sealed->{copied_files}++;
    }
    my $jar_source = $pinned{jar}{source};
    (my $quoted_jar = $jar_source) =~ s/'/'"'"'/g;
    my $shim = File::Spec->catfile($bin, 'jar');
    write_small_exclusive($shim, "#!/bin/sh\nexec '$quoted_jar' \"\$@\"\n", 0500);
    $pinned{jar}{exec_source} = $jar_source;
    $pinned{jar}{shim} = $shim;
    $pinned{jar}{shim_sha256} = sha256_file_streaming($shim);
    $pinned{jar}{shim_identity} = [Time::HiRes::lstat($shim)];
    $pinned{jar}{snapshot} = $shim;
    return $sealed->{inputs} = \%pinned;
}

sub write_small_exclusive {
    my ($path, $bytes, $mode) = @_;
    sysopen my $fh, $path, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot create pinned launcher $path: $!\n";
    binmode $fh, ':raw' or die "Cannot set raw launcher mode: $!\n";
    checked_print($fh, $bytes, "pinned launcher $path");
    checked_flush($fh, "pinned launcher $path");
    checked_close($fh, "pinned launcher $path");
    chmod $mode, $path or die "Cannot set pinned launcher mode: $!\n";
}

sub resolve_executable {
    my ($name) = @_;
    my @candidates = File::Spec->file_name_is_absolute($name)
        ? ($name)
        : map { File::Spec->catfile(length($_) ? $_ : '.', $name) }
            split /:/, ($ENV{PATH} // '');
    for my $candidate (@candidates) {
        next unless -f $candidate && -x $candidate;
        my $resolved = abs_path($candidate) or next;
        return $resolved if -f $resolved && -x $resolved;
    }
    die "Cannot resolve executable $name from PATH\n";
}

our $INPUT_OBSERVER;
sub assert_pinned_input {
    my ($record) = @_;
    $INPUT_OBSERVER->($record) if $INPUT_OBSERVER;
    if ($record->{exec_source}) {
        my @shim = Time::HiRes::lstat($record->{shim});
        die "Pinned jar launcher identity changed\n"
            unless @shim && same_file_identity($record->{shim_identity}, \@shim)
                && sha256_file_streaming($record->{shim}) eq $record->{shim_sha256};
        my @live = Time::HiRes::lstat($record->{exec_source});
        die "Pinned jar executable identity changed\n"
            unless @live && same_file_identity($record->{source_identity}, \@live)
                && sha256_file_streaming($record->{exec_source}) eq $record->{sha256};
    } else {
        assert_snapshot_record($record, $record->{label});
    }
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

sub sha256_file_streaming {
    my ($path) = @_;
    my $digest = Digest::SHA->new(256);
    sysopen my $fh, $path, O_RDONLY
        or die "Cannot hash $path: $!\n";
    binmode $fh, ':raw' or die "Cannot set raw hash mode for $path: $!\n";
    while (1) {
        my $count = sysread($fh, my $chunk, $STREAM_BUFFER);
        die "Cannot hash $path: $!\n" unless defined $count;
        last unless $count;
        $digest->add($chunk);
    }
    close $fh or die "Cannot close hashed file $path: $!\n";
    return $digest->hexdigest;
}

sub load_json_bounded {
    my ($path, $label) = @_;
    return decode_json_object(
        read_raw_bounded($path, $MAX_JSON_BYTES), $label, $path);
}

sub decode_json_object {
    my ($bytes, $label, $path) = @_;
    my $document = eval { JSON::PP->new->utf8->decode($bytes) };
    die "Invalid $label JSON in $path\n"
        unless $document && ref($document) eq 'HASH';
    return $document;
}

sub decode_json_value {
    my ($bytes, $label, $path) = @_;
    my $document = eval { JSON::PP->new->utf8->decode($bytes) };
    die "Invalid $label JSON in $path\n" unless defined $document && ref($document);
    return $document;
}

sub read_raw_bounded {
    my ($path, $limit) = @_;
    my @st = stat($path);
    die "Cannot inspect bounded input $path: $!\n" unless @st;
    die "Bounded input exceeds $limit bytes: $path\n" if $st[7] > $limit;
    sysopen my $fh, $path, O_RDONLY or die "Cannot read $path: $!\n";
    binmode $fh, ':raw' or die "Cannot set raw read mode for $path: $!\n";
    my $contents = '';
    while (1) {
        my $count = sysread($fh, my $chunk, 64 * 1024);
        die "Cannot read $path: $!\n" unless defined $count;
        last unless $count;
        $contents .= $chunk;
        die "Bounded input grew beyond $limit bytes: $path\n"
            if length($contents) > $limit;
    }
    close $fh or die "Cannot close $path: $!\n";
    return $contents;
}

sub files_equal_streaming {
    my ($left, $right) = @_;
    my @left_stat = stat($left);
    my @right_stat = stat($right);
    return 0 unless @left_stat && @right_stat && $left_stat[7] == $right_stat[7];
    open my $lfh, '<:raw', $left or die "Cannot compare $left: $!\n";
    open my $rfh, '<:raw', $right or die "Cannot compare $right: $!\n";
    my $equal = 1;
    while (1) {
        my $lc = sysread($lfh, my $lb, $STREAM_BUFFER);
        my $rc = sysread($rfh, my $rb, $STREAM_BUFFER);
        die "Cannot stream-compare files: $!\n" unless defined($lc) && defined($rc);
        if ($lc != $rc || ($lc && $lb ne $rb)) { $equal = 0; last }
        last unless $lc;
    }
    close $lfh or die "Cannot close compared file $left: $!\n";
    close $rfh or die "Cannot close compared file $right: $!\n";
    return $equal;
}

sub publish_atomic {
    my ($path, $bytes) = @_;
    my $absolute = File::Spec->rel2abs($path);
    my $directory = dirname($absolute);
    die "Output directory does not exist: $directory\n" unless -d $directory;
    die "Refusing to overwrite output $path\n" if lstat($absolute);
    my $nonce = secure_nonce();
    my $stage_dir = File::Spec->catdir($directory, ".phase36-stage-$$-$nonce");
    mkdir $stage_dir, 0700 or die "Cannot create private staging directory: $!\n";
    my $temporary = File::Spec->catfile($stage_dir, 'report.tmp');
    my $ready = File::Spec->catfile($stage_dir, 'report.ready');
    my $fh;
    my ($published, $linked) = (0, 0);
    my $ok = eval {
        sysopen $fh, $temporary, O_RDWR | O_CREAT | O_EXCL, 0600
            or die "Cannot create temporary output $temporary: $!\n";
        binmode $fh, ':raw' or die "Cannot set raw output mode $temporary: $!\n";
        checked_print($fh, $bytes, $temporary);
        checked_flush($fh, $temporary);
        my @pinned = Time::HiRes::stat($fh);
        die "Cannot stat staged output descriptor: $!\n" unless @pinned;
        die "Staged output has the wrong byte length\n" unless $pinned[7] == length($bytes);
        checked_rename($temporary, $ready);
        publication_observer('ready', $ready, $absolute, $fh);
        assert_staging_identity($ready, \@pinned, $bytes);
        checked_link($ready, $absolute);
        $linked = 1;
        publication_observer('linked', $ready, $absolute, $fh);
        assert_published_identity($absolute, \@pinned, $bytes);
        checked_unlink($ready, 'staged ready output');
        checked_rmdir($stage_dir, 'private staging directory');
        checked_close($fh, $temporary);
        undef $fh;
        assert_published_identity($absolute, \@pinned, $bytes);
        $published = 1;
        1;
    };
    my $error = $@;
    if (!$ok) {
        CORE::close($fh) if defined $fh;
        my @cleanup_errors;
        if ($linked && lstat($absolute)) {
            unlink($absolute) or push @cleanup_errors,
                "cannot remove failed authoritative output $absolute: $!";
        }
        unlink($temporary) if lstat($temporary);
        unlink($ready) if lstat($ready);
        rmdir($stage_dir) if -d $stage_dir;
        die $error . (@cleanup_errors ? join("\n", @cleanup_errors) . "\n" : '');
    }
    return $published;
}

our $PUBLICATION_OBSERVER;
sub publication_observer {
    $PUBLICATION_OBSERVER->(@_) if $PUBLICATION_OBSERVER;
}

sub secure_nonce {
    sysopen my $fh, '/dev/urandom', O_RDONLY
        or die "Cannot open secure random source: $!\n";
    my $count = sysread($fh, my $bytes, 16);
    close $fh or die "Cannot close secure random source: $!\n";
    die "Cannot read secure random nonce\n" unless defined($count) && $count == 16;
    return unpack('H*', $bytes);
}

sub assert_staging_identity {
    my ($path, $pinned, $bytes) = @_;
    my @path = Time::HiRes::lstat($path);
    die "Staging pathname was replaced before publication\n"
        unless @path && !S_ISLNK($path[2]) && S_ISREG($path[2])
            && $path[0] == $pinned->[0] && $path[1] == $pinned->[1]
            && $path[2] == $pinned->[2] && $path[7] == $pinned->[7]
            && sha256_file_streaming($path) eq sha256_hex($bytes);
}

sub assert_published_identity {
    my ($path, $pinned, $bytes) = @_;
    my @path = Time::HiRes::lstat($path);
    die "Published output identity or bytes changed\n"
        unless @path && !S_ISLNK($path[2]) && S_ISREG($path[2])
            && $path[0] == $pinned->[0] && $path[1] == $pinned->[1]
            && $path[7] == length($bytes)
            && sha256_file_streaming($path) eq sha256_hex($bytes);
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

sub checked_unlink {
    my ($path, $label) = @_;
    unlink $path or die "Cannot remove $label $path: $!\n";
}

sub checked_rmdir {
    my ($path, $label) = @_;
    rmdir $path or die "Cannot remove $label $path: $!\n";
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
