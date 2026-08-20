#!/usr/bin/env perl

use strict;
use warnings;
use Cwd qw(abs_path getcwd);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin qw($Bin);
use JSON::PP;

die "Usage: $0 <standalone.jar> <sbom.json>\n" unless @ARGV == 2;
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

my $sbom = load_json($sbom_file);
my $components = $sbom->{components};
die "Combined SBOM has no components array\n" unless ref $components eq 'ARRAY';
my $dependencies = $sbom->{dependencies};
die "Combined SBOM has no dependencies array\n" unless ref $dependencies eq 'ARRAY';
my $joni_ref = 'pkg:maven/org.jruby.joni/joni@2.2.7?type=jar';
my $jcodings_ref = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';
assert_unique_component_ids($components);
assert_component($components, 'org.jruby.joni', 'joni', '2.2.7', $joni_ref);
assert_component($components, 'org.jruby.jcodings', 'jcodings', '1.0.64', $jcodings_ref);
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

sub assert_component {
    my ($components, $group, $name, $version, $ref) = @_;
    my @matching = grep { ($_->{group} // '') eq $group && ($_->{name} // '') eq $name } @$components;
    die "Combined SBOM is missing vendored $name $version\n" unless @matching;
    die "Combined SBOM has duplicate $name components\n" unless @matching == 1;
    my $component = $matching[0];
    die "Combined SBOM has wrong $name version\n" unless ($component->{version} // '') eq $version;
    die "Combined SBOM has wrong $name bom-ref\n" unless ($component->{'bom-ref'} // '') eq $ref;
    die "Combined SBOM has wrong $name purl\n" unless ($component->{purl} // '') eq $ref;
}
