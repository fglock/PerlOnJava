#!/usr/bin/env perl
use strict;
use warnings;

use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin qw($Bin);

binmode STDOUT, ':raw';

my $root = File::Spec->rel2abs(File::Spec->catdir($Bin, '..', '..'));
my $snapshot = File::Spec->catdir($root, 'dev', 'unicode', '17.0.0');
my $perl_unicore = File::Spec->catdir($root, 'perl5', 'lib', 'unicore');
my $unicode_root = -f File::Spec->catfile($snapshot, 'Unikemet.txt')
        && -f File::Spec->catfile($snapshot, 'version')
    ? $snapshot
    : $perl_unicore;

my $version_path = File::Spec->catfile($unicode_root, 'version');
my $unikemet_path = File::Spec->catfile($unicode_root, 'Unikemet.txt');
my $mktables_path = -f File::Spec->catfile($unicode_root, 'mktables')
    ? File::Spec->catfile($unicode_root, 'mktables')
    : File::Spec->catfile($perl_unicore, 'mktables');

my $expected_version = '17.0.0';
my %expected_hash = (
    version => '8c30575264b2772c7a69c5bb6069a28f0e0a7a0df735871bde2d99ee674316ac',
    unikemet => '76a3081265e6eb673873f9c93d6f36062e82c7ed027c5c1a592accfbe48c20a5',
    mktables => 'e3ac360c03d18779fea6d6497fbbe53798135da55e3764d3c9f90a79bbf7e8b5',
);

sub read_pinned {
    my ($name, $path) = @_;
    open my $fh, '<:raw', $path or die "Can't read $path: $!\n";
    local $/;
    my $bytes = <$fh>;
    close $fh or die "Can't close $path: $!\n";
    my $actual = sha256_hex($bytes);
    die "$path SHA-256 mismatch: expected $expected_hash{$name}, found $actual\n"
        unless $actual eq $expected_hash{$name};
    return $bytes;
}

my $version_bytes = read_pinned('version', $version_path);
my $unikemet = read_pinned('unikemet', $unikemet_path);
my $mktables = read_pinned('mktables', $mktables_path);

(my $version = $version_bytes) =~ s/\r?\n\z//;
die "Expected Unicode $expected_version, found '$version'\n"
    unless $version eq $expected_version;

die "Unikemet source has the wrong Unicode header\n"
    unless $unikemet =~ /^# Unikemet-17\.0\.0\.txt$/m;
die "Unikemet source has the wrong date\n"
    unless $unikemet =~ /^# Date: 2025-07-21$/m;
die "Unikemet source is missing the Unicode copyright notice\n"
    unless $unikemet =~ /^# \x{c2}\x{a9} 2025 Unicode\x{c2}\x{ae}, Inc\.$/m;
die "Unikemet source is missing the Unicode trademark notice\n"
    unless $unikemet =~ /^# Unicode and the Unicode Logo are registered trademarks of Unicode, Inc\. in the U\.S\. and other countries\.$/m;
die "Unikemet source is missing the Unicode terms URL\n"
    unless $unikemet =~ m{^# For terms of use and license, see https://www\.unicode\.org/terms_of_use\.html$}m;
die "Unikemet source no longer declares kEH_Core C/L/default-N semantics\n"
    unless $unikemet =~ /^#\s*Core value: kEH_Core \(C for core, L for legacy, default is N\)$/m;

die "Pinned mktables no longer creates provisional enum kEH_Core default N\n"
    unless $mktables =~ /Core\s*=>\s*\{\s*type\s*=>\s*\$ENUM,\s*default\s*=>\s*'N'\s*\}/s
        && $mktables =~ /Property->new\("kEH_\$property"[\s\S]*?Status\s*=>\s*\$PROVISIONAL/s
        && $mktables =~ /\$keH_Core->add_match_table\('C'\);\s*\$keH_Core->add_match_table\('L'\);/s;

my %points = (C => [], L => []);
my $previous = -1;
for my $line (split /\n/, $unikemet) {
    $line =~ s/\r\z//;
    next if $line =~ /^\s*(?:#|\z)/;
    die "Malformed Unikemet record: $line\n"
        unless $line =~ /^U\+([0-9A-F]{4,6})\t([^\t]+)\t([^\t]*)\z/;
    my ($code_point, $tag, $value) = (hex($1), $2, $3);
    die sprintf("Unikemet code point U+%X exceeds Unicode\n", $code_point)
        if $code_point > 0x10ffff;
    next unless $tag eq 'kEH_Core';
    die "Unexpected explicit kEH_Core value '$value' at U+$1\n"
        unless exists $points{$value};
    die sprintf("Duplicate or out-of-order kEH_Core code point U+%X\n", $code_point)
        if $code_point <= $previous;
    push @{$points{$value}}, $code_point;
    $previous = $code_point;
}

die "Expected 4,403 kEH_Core=C records, found " . scalar(@{$points{C}}) . "\n"
    unless @{$points{C}} == 4_403;
die "Expected 96 kEH_Core=L records, found " . scalar(@{$points{L}}) . "\n"
    unless @{$points{L}} == 96;

sub coalesce_points {
    my ($points) = @_;
    my @ranges;
    for my $code_point (@$points) {
        if (@ranges && $ranges[-1][1] + 1 == $code_point) {
            $ranges[-1][1] = $code_point;
        } else {
            push @ranges, [$code_point, $code_point];
        }
    }
    return \@ranges;
}

my %ranges = map { $_ => coalesce_points($points{$_}) } qw(C L);
die "Expected 541 coalesced C ranges, found " . scalar(@{$ranges{C}}) . "\n"
    unless @{$ranges{C}} == 541;
die "Expected 68 coalesced L ranges, found " . scalar(@{$ranges{L}}) . "\n"
    unless @{$ranges{L}} == 68;

my %value_at = map { $_ => 'C' } @{$points{C}};
for my $code_point (@{$points{L}}) {
    die sprintf("Overlapping C/L membership at U+%X\n", $code_point)
        if exists $value_at{$code_point};
    $value_at{$code_point} = 'L';
}
sub value_at { return $value_at{$_[0]} // 'N' }

my %sentinel = (
    0x12fff => 'N', 0x13000 => 'C', 0x1305c => 'C',
    0x1305d => 'L', 0x1305e => 'L', 0x1305f => 'C',
    0x130a9 => 'C', 0x13423 => 'C', 0x13424 => 'L',
    0x13425 => 'C', 0x136ae => 'N', 0x142ad => 'N',
    0x143f9 => 'N', 0x143fa => 'C', 0x143fb => 'N',
    0xd800 => 'N', 0x10ffff => 'N',
);
for my $code_point (sort { $a <=> $b } keys %sentinel) {
    die sprintf("Unexpected kEH_Core value at U+%X: expected %s, found %s\n",
        $code_point, $sentinel{$code_point}, value_at($code_point))
        unless value_at($code_point) eq $sentinel{$code_point};
}

my $explicit = @{$points{C}} + @{$points{L}};
my $neither = 0x110000 - $explicit;
die "Unexpected explicit kEH_Core cardinality $explicit\n" unless $explicit == 4_499;
die "Unexpected default-N cardinality $neither\n" unless $neither == 1_109_613;

sub print_ranges {
    my ($name, $ranges) = @_;
    print "    private static final int[] ${name}_RANGES = {\n";
    for (my $index = 0; $index < @$ranges; $index += 4) {
        my $last = $index + 3 < $#$ranges ? $index + 3 : $#$ranges;
        print '        ', join(', ', map {
            sprintf('0x%X, 0x%X', @{$ranges->[$_]}[0, 1])
        } $index .. $last), ",\n";
    }
    print "    };\n\n";
}

print <<'HEADER';
/*
 * Generated from Perl 5.44's pinned Unicode Character Database by
 * dev/tools/generate_perl_unicode_egyptian_hieroglyph_core_data.pl.
 * Do not edit manually.
 *
 * Unicode data source copyright:
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in
 * the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;

final class PerlUnicodeEgyptianHieroglyphCoreData {
HEADER
print qq{    static final String UNICODE_VERSION = "$version";\n};
print qq{    static final String VERSION_SHA256 = "$expected_hash{version}";\n};
print qq{    static final String UNIKEMET_SHA256 = "$expected_hash{unikemet}";\n};
print qq{    static final String MKTABLES_SHA256 = "$expected_hash{mktables}";\n\n};
print <<'VALUES';
    private static final String PROPERTY = "kEH_Core";
    private static final String[] VALUES = {"C", "L", "N"};
    private static final int[] EXPECTED_RANGE_COUNTS = {541, 68, 474};
    private static final int[] EXPECTED_CARDINALITIES = {4_403, 96, 1_109_613};

VALUES
print_ranges('CORE', $ranges{C});
print_ranges('LEGACY', $ranges{L});
print <<'FOOTER';
    private static final UnicodeSet[] SETS = buildSets();

    static boolean isPropertyAlias(String propertyAlias) {
        return "kehcore".equals(loose(propertyAlias));
    }

    static String canonicalProperty(String propertyAlias) {
        return isPropertyAlias(propertyAlias) ? PROPERTY : null;
    }

    static UnicodeSet valueSet(String propertyAlias, String valueAlias) {
        if (!isPropertyAlias(propertyAlias)) return null;
        int valueId = valueId(valueAlias);
        return valueId < 0 ? null : SETS[valueId];
    }

    static String canonicalValue(String propertyAlias, String valueAlias) {
        if (!isPropertyAlias(propertyAlias)) return null;
        int valueId = valueId(valueAlias);
        return valueId < 0 ? null : VALUES[valueId];
    }

    static String shortValue(String propertyAlias, String valueAlias) {
        return canonicalValue(propertyAlias, valueAlias);
    }

    static String[] canonicalValues(String propertyAlias) {
        return isPropertyAlias(propertyAlias) ? VALUES.clone() : null;
    }

    static String[] wildcardValues(String propertyAlias) {
        return canonicalValues(propertyAlias);
    }

    static int expectedRangeCount(String valueAlias) {
        int valueId = valueId(valueAlias);
        return valueId < 0 ? -1 : EXPECTED_RANGE_COUNTS[valueId];
    }

    static int expectedCardinality(String valueAlias) {
        int valueId = valueId(valueAlias);
        return valueId < 0 ? -1 : EXPECTED_CARDINALITIES[valueId];
    }

    private static UnicodeSet[] buildSets() {
        UnicodeSet core = setFromRanges(CORE_RANGES);
        UnicodeSet legacy = setFromRanges(LEGACY_RANGES);
        if (core.containsSome(legacy)) {
            throw new IllegalStateException("Generated kEH_Core C/L sets overlap");
        }
        UnicodeSet neither = new UnicodeSet(0, 0x10ffff)
                .removeAll(core).removeAll(legacy).freeze();
        UnicodeSet[] sets = {core, legacy, neither};
        for (int i = 0; i < sets.length; i++) {
            if (sets[i].getRangeCount() != EXPECTED_RANGE_COUNTS[i]
                    || sets[i].size() != EXPECTED_CARDINALITIES[i]) {
                throw new IllegalStateException(
                        "Corrupt generated kEH_Core data for " + VALUES[i]);
            }
        }
        return sets;
    }

    private static UnicodeSet setFromRanges(int[] ranges) {
        UnicodeSet set = new UnicodeSet();
        for (int i = 0; i < ranges.length; i += 2) {
            set.add(ranges[i], ranges[i + 1]);
        }
        return set.freeze();
    }

    private static int valueId(String valueAlias) {
        String value = loose(valueAlias);
        if (value.equals("c")) return 0;
        if (value.equals("l")) return 1;
        if (value.equals("n")) return 2;
        return -1;
    }

    private static String loose(String alias) {
        if (alias == null) return "";
        StringBuilder normalized = new StringBuilder(alias.length());
        for (int i = 0; i < alias.length(); i++) {
            char character = alias.charAt(i);
            if (character == '_' || character == '-' || character == ' '
                    || (character >= '\t' && character <= '\r')) continue;
            normalized.append(character >= 'A' && character <= 'Z'
                    ? (char) (character + ('a' - 'A')) : character);
        }
        return normalized.toString();
    }

    private PerlUnicodeEgyptianHieroglyphCoreData() {
    }
}
FOOTER
