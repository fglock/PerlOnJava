#!/usr/bin/env perl

use strict;
use warnings;
use Cwd qw(abs_path getcwd);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin qw($Bin);
use Getopt::Long qw(GetOptions);
use JSON::PP;

my $strict;
GetOptions('strict' => \$strict)
    or die "Usage: $0 [--strict] <standalone.jar> <sbom.json>\n";
die "Usage: $0 [--strict] <standalone.jar> <sbom.json>\n" unless @ARGV == 2;
my ($jar_file, $sbom_file) = @ARGV;
die "Standalone JAR is missing or empty: $jar_file\n" unless -f $jar_file && -s $jar_file;
die "SBOM is missing or empty: $sbom_file\n" unless -f $sbom_file && -s $sbom_file;
$jar_file = abs_path($jar_file);
$sbom_file = abs_path($sbom_file);
my $root = abs_path(File::Spec->catdir($Bin, '..', '..'))
    or die "Cannot resolve repository root from $Bin\n";
my %notices = (
    'joni-LICENSE.txt' => File::Spec->catfile($root, 'third_party', 'joni', 'LICENSE'),
    'jcodings-LICENSE.txt' => File::Spec->catfile($root, 'third_party', 'licenses', 'jcodings-LICENSE.txt'),
    'joni-PERLONJAVA-NOTICE.md' => File::Spec->catfile($root, 'third_party', 'joni', 'PERLONJAVA-NOTICE.md'),
);

my %entries;
open my $jar_fh, '-|', 'jar', 'tf', $jar_file
    or die "Cannot list $jar_file: $!\n";
while (my $entry = <$jar_fh>) {
    chomp $entry;
    $entries{$entry}++;
}
close $jar_fh or die "Cannot list $jar_file: jar exited with status $?\n";

for my $entry (keys %entries) {
    next unless $entry =~ m{^(?:org/joni/|org/jcodings/|org/perlonjava/internal/(?:joni|jcodings)/)}
        && $entry =~ /\.class\z/;
    die "Standalone JAR contains duplicate class entry $entry\n" if $entries{$entry} != 1;
}
die "Standalone JAR contains unrelocated Joni classes\n"
    if grep { m{^org/joni/.*\.class\z} } keys %entries;
die "Standalone JAR contains unrelocated JCodings classes\n"
    if grep { m{^org/jcodings/.*\.class\z} } keys %entries;
die "Standalone JAR contains retired regex backend selector\n"
    if $entries{'org/perlonjava/runtime/regex/RegexBackendPolicy.class'};
die "Standalone JAR does not contain relocated Joni classes\n"
    unless grep { m{^org/perlonjava/internal/joni/.*\.class\z} } keys %entries;
die "Standalone JAR does not contain relocated JCodings classes\n"
    unless grep { m{^org/perlonjava/internal/jcodings/.*\.class\z} } keys %entries;

for my $notice (sort keys %notices) {
    die "Standalone JAR is missing $notice\n"
        unless $entries{"META-INF/licenses/$notice"};
    die "Standalone JAR contains duplicate notice $notice\n"
        unless $entries{"META-INF/licenses/$notice"} == 1;
    die "Standalone JAR notice bytes differ for $notice\n"
        unless jar_entry_bytes($jar_file, "META-INF/licenses/$notice") eq read_raw($notices{$notice});
}

if ($strict) {
    my $embedded_sbom = 'META-INF/sbom/sbom.json';
    my $embedded_sbom_count = $entries{$embedded_sbom} // 0;
    die "Standalone JAR is missing $embedded_sbom\n" unless $embedded_sbom_count;
    die "Standalone JAR contains duplicate $embedded_sbom\n"
        unless $embedded_sbom_count == 1;
    die "Standalone JAR embedded SBOM bytes differ from external merged SBOM\n"
        unless jar_entry_bytes($jar_file, $embedded_sbom) eq read_raw($sbom_file);
}

my $sbom = load_json($sbom_file);
my $components = $sbom->{components};
die "Combined SBOM has no components array\n" unless ref $components eq 'ARRAY';
my $dependencies = $sbom->{dependencies};
die "Combined SBOM has no dependencies array\n" unless ref $dependencies eq 'ARRAY';
my $joni_ref = $strict
    ? 'pkg:generic/perlonjava/joni-fork@2.2.7'
    : 'pkg:maven/org.jruby.joni/joni@2.2.7?type=jar';
my $jcodings_ref = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';
assert_unique_component_ids($components);
assert_merged_sbom($sbom, $components, $dependencies, $strict);
assert_no_legacy_joni_identity($components) if $strict;
my $joni = $strict
    ? assert_component($components, 'org.perlonjava.fork', 'joni-fork',
        '2.2.7', $joni_ref)
    : assert_component($components, 'org.jruby.joni', 'joni',
        '2.2.7', $joni_ref);
assert_component($components, 'org.jruby.jcodings', 'jcodings', '1.0.64', $jcodings_ref);
if ($strict) {
    assert_properties($joni, {
        'perlonjava:vendored' => 'true',
        'perlonjava:modified' => 'true',
        'perlonjava:vendored-source-path' => 'third_party/joni',
        'perlonjava:source-commit' => qr/\A[0-9a-f]{40}\z/,
        'perlonjava:upstream-maven-coordinate' => 'org.jruby.joni:joni:2.2.7',
        'perlonjava:upstream-tag' => 'joni-2.2.7',
        'perlonjava:upstream-commit' => '57fd57b4f977813a7b4b35e0179943b1f06f51d7',
    });
    my @root_relations = grep { ($_->{ref} // '') eq 'perlonjava' } @$dependencies;
    die "Combined SBOM is missing PerlOnJava -> Joni fork dependency edge\n"
        unless grep { $_ eq $joni_ref } @{$root_relations[0]{dependsOn}};
}
my @relations = grep { ($_->{ref} // '') eq $joni_ref } @$dependencies;
die "Combined SBOM is missing Joni dependency relation\n" unless @relations;
die "Combined SBOM has duplicate Joni dependency relations\n" unless @relations == 1;
die "Combined SBOM Joni dependency relation is malformed\n"
    unless ref $relations[0]{dependsOn} eq 'ARRAY';
die "Combined SBOM is missing Joni -> JCodings dependency edge\n"
    unless grep { $_ eq $jcodings_ref } @{$relations[0]{dependsOn}};

print "Joni packaging verification passed\n";

sub read_raw {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "Cannot read $file: $!\n";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $file: $!\n";
    return $contents;
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

sub load_json {
    my ($file) = @_;
    my $document = eval { JSON::PP->new->utf8->decode(read_raw($file)) };
    die "Malformed SBOM JSON in $file: $@\n" unless $document && ref $document eq 'HASH';
    return $document;
}

sub assert_unique_component_ids {
    my ($components) = @_;
    my (%refs, %purls);
    for my $component (@$components) {
        die "SBOM component is not an object\n" unless ref $component eq 'HASH';
        for my $field (['bom-ref', \%refs], ['purl', \%purls]) {
            my ($name, $seen) = @$field;
            next unless defined $component->{$name} && length $component->{$name};
            die "Combined SBOM has duplicate $name $component->{$name}\n"
                if $seen->{$component->{$name}}++;
        }
    }
}

sub assert_merged_sbom {
    my ($sbom, $components, $dependencies, $strict_mode) = @_;
    die "Combined SBOM is not CycloneDX\n"
        if $strict_mode && ($sbom->{bomFormat} // '') ne 'CycloneDX';
    my $metadata = $sbom->{metadata};
    my $root = ref $metadata eq 'HASH' ? $metadata->{component} : undef;
    die "Combined SBOM is missing canonical PerlOnJava metadata component\n"
        unless ref $root eq 'HASH'
            && ($root->{type} // '') eq 'application'
            && ($root->{'bom-ref'} // '') eq 'perlonjava'
            && ($root->{name} // '') eq 'perlonjava'
            && length($root->{version} // '')
            && ($root->{purl} // '') eq "pkg:generic/perlonjava\@$root->{version}";
    my $licenses = $root->{licenses};
    die "Combined SBOM canonical PerlOnJava metadata has no Artistic-2.0 license\n"
        unless ref $licenses eq 'ARRAY'
            && grep { ref $_ eq 'HASH'
                && ref $_->{license} eq 'HASH'
                && ($_->{license}{id} // '') eq 'Artistic-2.0' } @$licenses;

    my @bundled = grep {
        ($_->{'bom-ref'} // '') =~ /^perl:/
            || ($_->{purl} // '') =~ m{^pkg:cpan/}
    } @$components;
    die "Combined SBOM has no bundled Perl components\n" unless @bundled;
    for my $component (@bundled) {
        my $name = $component->{name} // '';
        my $version = $component->{version} // '';
        (my $ref_name = $name) =~ s/::/-/g;
        die "Combined SBOM has malformed bundled Perl component identity\n"
            unless ($component->{type} // '') eq 'library'
                && length($name) && length($version)
                && ($component->{'bom-ref'} // '') eq "perl:$ref_name"
                && ($component->{purl} // '') eq "pkg:cpan/$name\@$version";
    }

    my @root_relations = grep { ($_->{ref} // '') eq 'perlonjava' } @$dependencies;
    die "Combined SBOM is missing PerlOnJava root dependency relation\n"
        unless @root_relations;
    die "Combined SBOM has duplicate PerlOnJava root dependency relations\n"
        unless @root_relations == 1;
    my $depends_on = $root_relations[0]{dependsOn};
    die "Combined SBOM PerlOnJava root dependency relation is malformed\n"
        unless ref $depends_on eq 'ARRAY';
    my %root_dependency = map { $_ => 1 } @$depends_on;
    for my $component (@bundled) {
        die "Combined SBOM root omits bundled Perl component $component->{'bom-ref'}\n"
            unless $root_dependency{$component->{'bom-ref'}};
    }
}

sub assert_component {
    my ($components, $group, $name, $version, $ref) = @_;
    my @matching = grep { ($_->{group} // '') eq $group && ($_->{name} // '') eq $name } @$components;
    die "Combined SBOM is missing vendored $name $version\n" unless @matching;
    die "Combined SBOM has duplicate $name components\n" unless @matching == 1;
    my $component = $matching[0];
    die "Combined SBOM has wrong $name version\n" unless ($component->{version} // '') eq $version;
    die "Combined SBOM has wrong $name bom-ref\n" unless ($component->{'bom-ref'} // '') eq $ref;
    die "Combined SBOM has wrong $name purl\n" unless ($component->{purl} // '') eq $ref;
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
        unless ref $properties eq 'ARRAY';
    for my $property (@$properties) {
        next unless ref $property eq 'HASH';
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
