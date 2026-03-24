#!/usr/bin/env perl
#
# generate-perl-sbom.pl - Generate CycloneDX SBOM for bundled Perl modules
#
# This script scans the src/main/perl/lib/ directory and generates a CycloneDX
# 1.6 compliant SBOM in JSON format for all bundled Perl modules.
#
# Usage:
#   perl dev/tools/generate-perl-sbom.pl > build/reports/perl-bom.json
#
# Environment variables:
#   VERSION - Override the version string (default: 5.42.0)

use strict;
use warnings;
use File::Find;
use File::Basename;
use Cwd 'abs_path';
use POSIX qw(strftime);

# Find project root (where this script lives is dev/tools/)
my $script_dir = dirname(abs_path($0));
my $project_root = abs_path("$script_dir/../..");
my $lib_dir = "$project_root/src/main/perl/lib";

die "Error: Perl library directory not found: $lib_dir\n" unless -d $lib_dir;

# Configuration
my $version = $ENV{VERSION} // '5.42.0';
my $timestamp = strftime('%Y-%m-%dT%H:%M:%SZ', gmtime());
my $serial_number = generate_uuid();

# Collect all Perl modules
my @modules;
find(\&wanted, $lib_dir);

sub wanted {
    return unless /\.pm$/;
    my $path = $File::Find::name;
    
    # Convert path to module name: lib/Foo/Bar.pm -> Foo::Bar
    my $rel_path = $path;
    $rel_path =~ s{^\Q$lib_dir\E/?}{};
    
    my $module = $rel_path;
    $module =~ s{/}{::}g;
    $module =~ s{\.pm$}{};
    
    # Extract version from module
    my $mod_version = extract_version($path) // 'bundled';
    
    # Try to detect license from the module
    my $license = detect_license($path);
    
    push @modules, {
        name    => $module,
        version => $mod_version,
        license => $license,
        path    => $rel_path,
    };
}

# Sort modules alphabetically
@modules = sort { $a->{name} cmp $b->{name} } @modules;

# Generate CycloneDX JSON
print generate_cyclonedx_json();

sub extract_version {
    my ($path) = @_;
    open my $fh, '<', $path or return;
    local $/ = undef;  # slurp mode
    my $content = <$fh>;
    close $fh;
    
    # Match various version patterns:
    # our $VERSION = '1.23';
    # $VERSION = "1.23";
    # $VERSION = 1.23;
    # use version; our $VERSION = version->declare("v1.2.3");
    if ($content =~ /\$VERSION\s*=\s*['"]?v?([0-9][0-9._]*)['"]?/m) {
        return $1;
    }
    # version->declare pattern
    if ($content =~ /version->(?:new|declare)\s*\(\s*['"]([^'"]+)['"]\s*\)/m) {
        return $1;
    }
    return;
}

sub detect_license {
    my ($path) = @_;
    open my $fh, '<', $path or return;
    
    # Read first 200 lines for license detection
    my $lines = 0;
    while (<$fh>) {
        last if ++$lines > 200;
        
        # Common license patterns
        return 'Artistic-2.0' if /Artistic\s*(?:License\s*)?2/i;
        return 'Artistic-1.0-Perl' if /Artistic\s*(?:License)?(?:\s*1)?(?!\s*2)/i && !/Artistic.*2/i;
        return 'GPL-1.0-or-later' if /GPL.*version\s*1/i || /GNU.*General.*Public.*License/i;
        return 'MIT' if /\bMIT\s+License\b/i;
        return 'BSD-3-Clause' if /BSD\s*(?:3|three)/i;
        return 'Apache-2.0' if /Apache\s*(?:License\s*)?(?:Version\s*)?2/i;
    }
    close $fh;
    return;
}

sub generate_uuid {
    # Generate a UUID v4 (random)
    my @hex = ('0'..'9', 'a'..'f');
    my $uuid = '';
    for my $i (1..32) {
        $uuid .= $hex[int(rand(16))];
    }
    # Format as UUID: 8-4-4-4-12
    $uuid = substr($uuid, 0, 8) . '-' .
            substr($uuid, 8, 4) . '-' .
            '4' . substr($uuid, 13, 3) . '-' .  # version 4
            $hex[8 + int(rand(4))] . substr($uuid, 17, 3) . '-' .  # variant
            substr($uuid, 20, 12);
    return "urn:uuid:$uuid";
}

sub json_escape {
    my ($str) = @_;
    return '' unless defined $str;
    $str =~ s/\\/\\\\/g;
    $str =~ s/"/\\"/g;
    $str =~ s/\n/\\n/g;
    $str =~ s/\r/\\r/g;
    $str =~ s/\t/\\t/g;
    return $str;
}

sub generate_cyclonedx_json {
    my @lines;
    
    # Header
    push @lines, '{';
    push @lines, '  "$schema": "http://cyclonedx.org/schema/bom-1.6.schema.json",';
    push @lines, '  "bomFormat": "CycloneDX",';
    push @lines, '  "specVersion": "1.6",';
    push @lines, qq{  "serialNumber": "$serial_number",};
    push @lines, '  "version": 1,';
    
    # Metadata
    push @lines, '  "metadata": {';
    push @lines, qq{    "timestamp": "$timestamp",};
    push @lines, '    "tools": {';
    push @lines, '      "components": [';
    push @lines, '        {';
    push @lines, '          "type": "application",';
    push @lines, '          "name": "generate-perl-sbom.pl",';
    push @lines, '          "version": "1.0.0",';
    push @lines, '          "description": "PerlOnJava Perl SBOM generator"';
    push @lines, '        }';
    push @lines, '      ]';
    push @lines, '    },';
    push @lines, '    "component": {';
    push @lines, '      "type": "application",';
    push @lines, '      "bom-ref": "perlonjava-perl-modules",';
    push @lines, '      "name": "perlonjava-perl-modules",';
    push @lines, qq{      "version": "$version",};
    push @lines, '      "description": "Bundled Perl modules for PerlOnJava",';
    push @lines, '      "licenses": [';
    push @lines, '        {';
    push @lines, '          "license": {';
    push @lines, '            "id": "Artistic-2.0"';
    push @lines, '          }';
    push @lines, '        }';
    push @lines, '      ],';
    push @lines, qq{      "purl": "pkg:generic/perlonjava-perl-modules\@$version"};
    push @lines, '    },';
    push @lines, '    "manufacture": {';
    push @lines, '      "name": "PerlOnJava Project",';
    push @lines, '      "url": ["https://github.com/fglock/PerlOnJava"]';
    push @lines, '    }';
    push @lines, '  },';
    
    # Components
    push @lines, '  "components": [';
    
    my @components;
    for my $mod (@modules) {
        my $name = json_escape($mod->{name});
        my $ver = json_escape($mod->{version});
        my $bom_ref = "perl:" . $name;
        $bom_ref =~ s/::/-/g;
        
        my @comp_lines;
        push @comp_lines, '    {';
        push @comp_lines, '      "type": "library",';
        push @comp_lines, qq{      "bom-ref": "$bom_ref",};
        push @comp_lines, qq{      "name": "$name",};
        push @comp_lines, qq{      "version": "$ver",};
        
        # Add license if detected
        if ($mod->{license}) {
            my $lic = json_escape($mod->{license});
            push @comp_lines, qq{      "purl": "pkg:cpan/$name\@$ver",};
            push @comp_lines, '      "licenses": [';
            push @comp_lines, '        {';
            push @comp_lines, '          "license": {';
            push @comp_lines, qq{            "id": "$lic"};
            push @comp_lines, '          }';
            push @comp_lines, '        }';
            push @comp_lines, '      ]';
        } else {
            push @comp_lines, qq{      "purl": "pkg:cpan/$name\@$ver"};
        }
        
        push @comp_lines, '    }';
        push @components, join("\n", @comp_lines);
    }
    
    push @lines, join(",\n", @components);
    push @lines, '  ],';
    
    # Dependencies
    push @lines, '  "dependencies": [';
    push @lines, '    {';
    push @lines, '      "ref": "perlonjava-perl-modules",';
    push @lines, '      "dependsOn": [';
    
    my @dep_refs;
    for my $mod (@modules) {
        my $bom_ref = "perl:" . $mod->{name};
        $bom_ref =~ s/::/-/g;
        push @dep_refs, qq{        "$bom_ref"};
    }
    push @lines, join(",\n", @dep_refs);
    push @lines, '      ]';
    push @lines, '    }';
    push @lines, '  ]';
    push @lines, '}';
    
    return join("\n", @lines) . "\n";
}
