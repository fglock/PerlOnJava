#!/usr/bin/env perl
#
# merge-sbom.pl - Merge Java and Perl SBOMs into a unified CycloneDX SBOM
#
# Usage:
#   perl dev/tools/merge-sbom.pl \
#       build/reports/bom.json \
#       build/reports/perl-bom.json \
#       > build/reports/sbom.json
#
# This script merges two CycloneDX JSON SBOMs into a single unified SBOM.
# It combines components from both sources and creates a new root component
# representing the complete PerlOnJava distribution.

use strict;
use warnings;
use JSON::PP;
use POSIX qw(strftime);
use FindBin qw($Bin);

die "Usage: $0 <java-bom.json> <perl-bom.json>\n" unless @ARGV == 2;

my ($java_bom_file, $perl_bom_file) = @ARGV;

# Read and parse both SBOMs
my $java_bom = read_json($java_bom_file);
my $perl_bom = read_json($perl_bom_file);

# Generate new metadata
my $timestamp = strftime('%Y-%m-%dT%H:%M:%SZ', gmtime());
my $serial_number = generate_uuid();

# Get version from Java BOM metadata
my $version = $java_bom->{metadata}{component}{version} // '5.44.1';

# Build merged SBOM
my $merged = {
    '$schema'     => 'http://cyclonedx.org/schema/bom-1.6.schema.json',
    bomFormat     => 'CycloneDX',
    specVersion   => '1.6',
    serialNumber  => $serial_number,
    version       => 1,
    metadata      => {
        timestamp => $timestamp,
        tools     => {
            components => [
                {
                    type        => 'application',
                    name        => 'merge-sbom.pl',
                    version     => '1.0.0',
                    description => 'PerlOnJava SBOM merger'
                }
            ]
        },
        component => {
            type        => 'application',
            'bom-ref'   => 'perlonjava',
            name        => 'perlonjava',
            version     => $version,
            description => 'Perl compiler and runtime for the JVM',
            licenses    => [
                {
                    license => {
                        id => 'Artistic-2.0'
                    }
                }
            ],
            purl => "pkg:generic/perlonjava\@$version",
            externalReferences => [
                {
                    type => 'website',
                    url  => 'https://github.com/fglock/PerlOnJava'
                },
                {
                    type => 'vcs',
                    url  => 'https://github.com/fglock/PerlOnJava.git'
                }
            ]
        },
        manufacture => {
            name => 'PerlOnJava Project',
            url  => ['https://github.com/fglock/PerlOnJava']
        }
    },
    components   => [],
    dependencies => []
};

# Collect all components
my @all_components;
my @root_deps;

my $joni_ref = 'pkg:generic/perlonjava/joni-fork@2.2.7';
my $jcodings_ref = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';
my $joni_source_commit = source_commit();

# Add Java components
if ($java_bom->{components}) {
    for my $comp (@{$java_bom->{components}}) {
        push @all_components, $comp;
        push @root_deps, $comp->{'bom-ref'} if $comp->{'bom-ref'};
    }
}

# Joni is compiled from the pinned source snapshot in third_party/joni rather
# than resolved as a binary dependency, so CycloneDX cannot discover it from
# runtimeClasspath. Record the vendored component explicitly.
push @all_components, {
    type        => 'library',
    'bom-ref'   => $joni_ref,
    group       => 'org.perlonjava.fork',
    name        => 'joni-fork',
    version     => '2.2.7',
    description => 'PerlOnJava-maintained source fork of Joni with Perl-compatible parsing, Unicode, diagnostics, control-flow, and match-time extensions',
    licenses    => [
        {
            license => {
                id => 'MIT'
            }
        }
    ],
    purl => $joni_ref,
    externalReferences => [
        {
            type => 'website',
            url  => 'https://github.com/jruby/joni'
        },
        {
            type => 'vcs',
            url  => 'https://github.com/jruby/joni.git'
        }
    ],
    properties => [
        {
            name  => 'perlonjava:vendored',
            value => 'true'
        },
        {
            name  => 'perlonjava:modified',
            value => 'true'
        },
        {
            name  => 'perlonjava:vendored-source-path',
            value => 'third_party/joni'
        },
        {
            name  => 'perlonjava:source-commit',
            value => $joni_source_commit
        },
        {
            name  => 'perlonjava:upstream-maven-coordinate',
            value => 'org.jruby.joni:joni:2.2.7'
        },
        {
            name  => 'perlonjava:upstream-tag',
            value => 'joni-2.2.7'
        },
        {
            name  => 'perlonjava:upstream-commit',
            value => '57fd57b4f977813a7b4b35e0179943b1f06f51d7'
        }
    ]
};
push @root_deps, $joni_ref;

# Add Perl components
if ($perl_bom->{components}) {
    for my $comp (@{$perl_bom->{components}}) {
        push @all_components, $comp;
        push @root_deps, $comp->{'bom-ref'} if $comp->{'bom-ref'};
    }
}

$merged->{components} = \@all_components;

# Build dependency tree - root depends on all components
$merged->{dependencies} = [
    {
        ref       => 'perlonjava',
        dependsOn => \@root_deps
    },
    {
        ref       => $joni_ref,
        dependsOn => [$jcodings_ref]
    }
];

# Output merged SBOM
my $json = JSON::PP->new->utf8->pretty->canonical;
print $json->encode($merged);

sub read_json {
    my ($file) = @_;
    open my $fh, '<', $file or die "Cannot open $file: $!\n";
    local $/;
    my $content = <$fh>;
    close $fh;
    return JSON::PP->new->utf8->decode($content);
}

sub generate_uuid {
    my @hex = ('0'..'9', 'a'..'f');
    my $uuid = '';
    for (1..32) {
        $uuid .= $hex[int(rand(16))];
    }
    $uuid = substr($uuid, 0, 8) . '-' .
            substr($uuid, 8, 4) . '-' .
            '4' . substr($uuid, 13, 3) . '-' .
            $hex[8 + int(rand(4))] . substr($uuid, 17, 3) . '-' .
            substr($uuid, 20, 12);
    return "urn:uuid:$uuid";
}

sub source_commit {
    my $override = $ENV{PERLONJAVA_SOURCE_COMMIT};
    if (defined $override && length $override) {
        die "PERLONJAVA_SOURCE_COMMIT is not a full Git SHA\n"
            unless $override =~ /\A[0-9a-f]{40}\z/;
        return $override;
    }

    my $root = "$Bin/../..";
    open my $fh, '-|', 'git', '-C', $root, 'rev-parse', '--verify', 'HEAD'
        or die "Cannot resolve PerlOnJava source commit: $!\n";
    my $commit = <$fh> // '';
    close $fh or die "Cannot resolve PerlOnJava source commit: git exited with status $?\n";
    chomp $commit;
    die "PerlOnJava source commit is not a full Git SHA\n"
        unless $commit =~ /\A[0-9a-f]{40}\z/;
    return $commit;
}
