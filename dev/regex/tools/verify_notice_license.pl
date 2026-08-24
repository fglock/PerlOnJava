#!/usr/bin/env perl

use strict;
use warnings;

use Cwd qw(abs_path getcwd);
use Digest::SHA qw(sha256_hex);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin qw($Bin);
use Getopt::Long qw(GetOptions);
use JSON::PP;

my %option;
my $help;
GetOptions(
    'strict' => \$option{strict},
    'jar=s' => \$option{jar},
    'sbom=s' => \$option{sbom},
    'output=s' => \$option{output},
    'source-root=s' => \$option{source_root},
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;
for my $required (qw(jar sbom output)) {
    die "--$required is required\n"
        unless defined $option{$required} && length $option{$required};
}

my $root = abs_path($option{source_root} // File::Spec->catdir($Bin, '..', '..', '..'))
    or die "Cannot resolve source root\n";
my $jar = existing_file($option{jar}, 'standalone JAR');
my $sbom_file = existing_file($option{sbom}, 'merged SBOM');
my $initial_jar_sha256 = sha256_file($jar);
my $initial_sbom_sha256 = sha256_file($sbom_file);
my $output = File::Spec->rel2abs($option{output});
die "Refusing to overwrite output $output\n" if -e $output;

my @notice_contract = (
    {
        id => 'joni-license',
        source => File::Spec->catfile($root, 'third_party', 'joni', 'LICENSE'),
        entry => 'META-INF/licenses/joni-LICENSE.txt',
        required => [qr/\bMIT License\b/, qr/Copyright \(c\) 2017 JRuby Team/,
            qr/Permission is hereby granted, free of charge/],
        license => 'MIT',
    },
    {
        id => 'joni-notice',
        source => File::Spec->catfile($root, 'third_party', 'joni',
            'PERLONJAVA-NOTICE.md'),
        entry => 'META-INF/licenses/joni-PERLONJAVA-NOTICE.md',
        required => [qr/Modified Joni distribution/, qr/JRuby project/,
            qr/PerlOnJava project/, qr{https://github\.com/jruby/joni},
            qr/original MIT License/],
        license => 'notice',
    },
    {
        id => 'jcodings-license',
        source => File::Spec->catfile($root, 'third_party', 'licenses',
            'jcodings-LICENSE.txt'),
        entry => 'META-INF/licenses/jcodings-LICENSE.txt',
        required => [qr/Copyright \(c\) 2025 JRuby Team/,
            qr/Permission is hereby granted, free of charge/],
        license => 'MIT',
    },
);

my (@source_records, %source_bytes);
for my $contract (@notice_contract) {
    my $file = existing_file($contract->{source}, "$contract->{id} source");
    my $bytes = read_raw($file);
    die "$contract->{id} source is blank\n" unless $bytes =~ /\S/;
    for my $pattern (@{$contract->{required}}) {
        die "$contract->{id} source is missing required authorship/license material\n"
            unless $bytes =~ $pattern;
    }
    $source_bytes{$contract->{id}} = $bytes;
    push @source_records, {
        id => $contract->{id}, path => $file, jar_entry => $contract->{entry},
        sha256 => sha256_hex($bytes), size => length($bytes),
        license => $contract->{license},
    };
}

my %entries = jar_entries($jar);
for my $contract (@notice_contract) {
    my $count = $entries{$contract->{entry}} // 0;
    die "Standalone JAR is missing $contract->{entry}\n" unless $count;
    die "Standalone JAR has duplicate $contract->{entry}\n" unless $count == 1;
    my $jar_bytes = jar_entry_bytes($jar, $contract->{entry});
    die "Standalone JAR notice bytes differ for $contract->{id}\n"
        unless $jar_bytes eq $source_bytes{$contract->{id}};
}

if ($option{strict}) {
    my $embedded_sbom = 'META-INF/sbom/sbom.json';
    my $embedded_sbom_count = $entries{$embedded_sbom} // 0;
    die "Standalone JAR is missing $embedded_sbom\n" unless $embedded_sbom_count;
    die "Standalone JAR has duplicate $embedded_sbom\n"
        unless $embedded_sbom_count == 1;
    die "Standalone JAR embedded SBOM bytes differ from external merged SBOM\n"
        unless jar_entry_bytes($jar, $embedded_sbom) eq read_raw($sbom_file);
}

my $sbom = load_json($sbom_file);
die "Combined SBOM is not CycloneDX\n"
    unless ($sbom->{bomFormat} // '') eq 'CycloneDX';
die "Combined SBOM has no metadata component\n"
    unless ref($sbom->{metadata}) eq 'HASH'
        && ref($sbom->{metadata}{component}) eq 'HASH';
die "Combined SBOM root component is not perlonjava\n"
    unless ($sbom->{metadata}{component}{'bom-ref'} // '') eq 'perlonjava';
my $components = $sbom->{components};
die "Combined SBOM has no components array\n" unless ref($components) eq 'ARRAY';
my $dependencies = $sbom->{dependencies};
die "Combined SBOM has no dependencies array\n" unless ref($dependencies) eq 'ARRAY';

my $joni_ref = $option{strict}
    ? 'pkg:generic/perlonjava/joni-fork@2.2.7'
    : 'pkg:maven/org.jruby.joni/joni@2.2.7?type=jar';
my $jcodings_ref = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';
assert_unique_components($components);
assert_no_legacy_joni_identity($components) if $option{strict};
my $joni = $option{strict}
    ? assert_component($components, 'org.perlonjava.fork', 'joni-fork', '2.2.7',
        $joni_ref, 'MIT', 1)
    : assert_component($components, 'org.jruby.joni', 'joni', '2.2.7',
        $joni_ref, 'MIT', 1);
my $jcodings = assert_component($components, 'org.jruby.jcodings', 'jcodings',
    '1.0.64', $jcodings_ref, 'MIT', 0);
assert_properties($joni, {
        'perlonjava:vendored' => 'true',
        'perlonjava:modified' => 'true',
        'perlonjava:vendored-source-path' => 'third_party/joni',
        'perlonjava:source-commit' => qr/\A[0-9a-f]{40}\z/,
        'perlonjava:upstream-maven-coordinate' => 'org.jruby.joni:joni:2.2.7',
        'perlonjava:upstream-tag' => 'joni-2.2.7',
        'perlonjava:upstream-commit' => '57fd57b4f977813a7b4b35e0179943b1f06f51d7',
    }) if $option{strict};
assert_relation($dependencies, 'perlonjava', $joni_ref, 'root -> Joni');
assert_relation($dependencies, $joni_ref, $jcodings_ref, 'Joni -> JCodings');

for my $contract (@notice_contract) {
    die "$contract->{id} source mutated during verification\n"
        unless read_raw($contract->{source}) eq $source_bytes{$contract->{id}};
}
my $jar_sha256 = sha256_file($jar);
my $sbom_sha256 = sha256_file($sbom_file);
die "Standalone JAR mutated during verification\n"
    unless $jar_sha256 eq $initial_jar_sha256;
die "Merged SBOM mutated during verification\n"
    unless $sbom_sha256 eq $initial_sbom_sha256;

my $document = {
    schema_version => 1,
    kind => 'notice-license',
    verified => JSON::PP::true,
    missing_notices => 0,
    changed_notices => 0,
    missing_licenses => 0,
    changed_licenses => 0,
    jar_path => $jar,
    jar_sha256 => $jar_sha256,
    sbom_path => $sbom_file,
    sbom_sha256 => $sbom_sha256,
    source_root => $root,
    notices => \@source_records,
    components => [
        component_record($joni),
        component_record($jcodings),
    ],
    relationships => [
        { from => 'perlonjava', to => $joni_ref },
        { from => $joni_ref, to => $jcodings_ref },
    ],
};
write_json_exclusive($output, $document);
print "$output\n";

sub jar_entries {
    my ($file) = @_;
    my %entries;
    open my $fh, '-|', 'jar', 'tf', $file
        or die "Cannot list $file: $!\n";
    while (my $entry = <$fh>) {
        chomp $entry;
        $entries{$entry}++;
    }
    close $fh or die "Cannot list $file: jar exited with status $?\n";
    return %entries;
}

sub jar_entry_bytes {
    my ($file, $entry) = @_;
    my $directory = tempdir(CLEANUP => 1);
    my $original = getcwd();
    chdir $directory or die "Cannot enter temporary directory: $!\n";
    my $status = system('jar', 'xf', $file, $entry);
    my $error = $!;
    chdir $original or die "Cannot return to $original: $!\n";
    die "Cannot extract $entry from $file: $error\n" if $status != 0;
    return read_raw(File::Spec->catfile($directory, split m{/}, $entry));
}

sub assert_unique_components {
    my ($components) = @_;
    my (%refs, %purls);
    for my $component (@$components) {
        die "SBOM component is not an object\n" unless ref($component) eq 'HASH';
        for my $field (['bom-ref', \%refs], ['purl', \%purls]) {
            my ($name, $seen) = @$field;
            next unless defined($component->{$name}) && length($component->{$name});
            die "Combined SBOM has duplicate $name $component->{$name}\n"
                if $seen->{$component->{$name}}++;
        }
    }
}

sub assert_component {
    my ($components, $group, $name, $version, $ref, $license, $vendored) = @_;
    my @matching = grep {
        ($_->{group} // '') eq $group && ($_->{name} // '') eq $name
    } @$components;
    die "Combined SBOM is missing $name component (dependency-only BOM)\n"
        unless @matching;
    die "Combined SBOM has duplicate $name components\n" unless @matching == 1;
    my $component = $matching[0];
    die "Combined SBOM has wrong $name version\n"
        unless ($component->{version} // '') eq $version;
    die "Combined SBOM has wrong $name bom-ref\n"
        unless ($component->{'bom-ref'} // '') eq $ref;
    die "Combined SBOM has wrong $name purl\n"
        unless ($component->{purl} // '') eq $ref;
    my @licenses = map { ref($_) eq 'HASH' && ref($_->{license}) eq 'HASH'
        ? ($_->{license}{id} // '') : '' } @{$component->{licenses} // []};
    die "Combined SBOM has wrong or missing $name license\n"
        unless @licenses == 1 && $licenses[0] eq $license;
    if ($vendored) {
        my @values = map { ref($_) eq 'HASH' && ($_->{name} // '') eq 'perlonjava:vendored'
            ? ($_->{value} // '') : () } @{$component->{properties} // []};
        die "Combined SBOM is missing Joni vendored authorship metadata\n"
            unless @values == 1 && $values[0] eq 'true';
    }
    return $component;
}

sub assert_no_legacy_joni_identity {
    my ($components) = @_;
    my $legacy = 'pkg:maven/org.jruby.joni/joni@2.2.7?type=jar';
    die "Combined SBOM claims the modified Joni fork is the upstream Maven artifact\n"
        if grep {
            ($_->{'bom-ref'} // '') eq $legacy
                || ($_->{purl} // '') eq $legacy
                || (($_->{group} // '') eq 'org.jruby.joni'
                    && ($_->{name} // '') eq 'joni')
        } @$components;
}

sub assert_properties {
    my ($component, $required) = @_;
    my %values;
    my $properties = $component->{properties};
    die "Combined SBOM Joni fork has no provenance properties\n"
        unless ref($properties) eq 'ARRAY';
    for my $property (@$properties) {
        next unless ref($property) eq 'HASH';
        my $name = $property->{name} // '';
        next unless exists $required->{$name};
        push @{$values{$name}}, $property->{value} // '';
    }
    for my $name (sort keys %$required) {
        my $found = $values{$name} // [];
        die "Combined SBOM Joni fork has missing or duplicate $name property\n"
            unless @$found == 1;
        my $expected = $required->{$name};
        my $matches = ref($expected) eq 'Regexp'
            ? $found->[0] =~ $expected
            : $found->[0] eq $expected;
        die "Combined SBOM Joni fork has wrong $name property\n" unless $matches;
    }
}

sub assert_relation {
    my ($dependencies, $from, $to, $label) = @_;
    my @relations = grep { ref($_) eq 'HASH' && ($_->{ref} // '') eq $from }
        @$dependencies;
    die "Combined SBOM is missing $label dependency relation\n" unless @relations;
    die "Combined SBOM has duplicate $label dependency relations\n"
        unless @relations == 1;
    die "Combined SBOM $label dependency relation is malformed\n"
        unless ref($relations[0]{dependsOn}) eq 'ARRAY';
    my @edges = grep { defined($_) && $_ eq $to } @{$relations[0]{dependsOn}};
    die "Combined SBOM is missing $label dependency edge\n" unless @edges;
    die "Combined SBOM has duplicate $label dependency edge\n" unless @edges == 1;
}

sub component_record {
    my ($component) = @_;
    return {
        group => $component->{group}, name => $component->{name},
        version => $component->{version}, bom_ref => $component->{'bom-ref'},
        purl => $component->{purl}, license => $component->{licenses}[0]{license}{id},
    };
}

sub existing_file {
    my ($file, $label) = @_;
    my $path = abs_path($file) or die "Cannot resolve $label $file\n";
    die "$label is missing or empty: $file\n" unless -f $path && -s $path;
    return $path;
}

sub load_json {
    my ($file) = @_;
    my $document = eval { JSON::PP->new->utf8->decode(read_raw($file)) };
    die "Malformed SBOM JSON in $file: $@\n"
        unless $document && ref($document) eq 'HASH';
    return $document;
}

sub sha256_file {
    return sha256_hex(read_raw($_[0]));
}

sub read_raw {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "Cannot read $file: $!\n";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $file: $!\n";
    return $contents;
}

sub write_json_exclusive {
    my ($file, $document) = @_;
    require Fcntl;
    sysopen my $fh, $file, Fcntl::O_WRONLY() | Fcntl::O_CREAT() | Fcntl::O_EXCL(), 0600
        or die "Cannot exclusively create $file: $!\n";
    binmode $fh, ':raw';
    print {$fh} JSON::PP->new->utf8->canonical->pretty->encode($document)
        or die "Cannot write $file: $!\n";
    close $fh or die "Cannot close $file: $!\n";
}

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: verify_notice_license.pl [--strict] --jar FILE --sbom FILE --output FILE
       [--source-root DIR]

Fail-closed verification of the vendored Joni/JCodings source notices, exact
standalone-JAR notice bytes, licensed CycloneDX components, and canonical
PerlOnJava -> Joni -> JCodings dependency relationships. Writes canonical JSON
details suitable for the Regex implementation notice-license acceptance gate.
USAGE
    exit $status;
}
