#!/usr/bin/env perl
use strict;
use warnings;

use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin qw($Bin);

my $root = File::Spec->rel2abs(File::Spec->catdir($Bin, '..', '..'));
my $ucd = File::Spec->catdir($root, 'perl5', 'lib', 'unicore');
my $expected_version = '17.0.0';

my %input = (
    version => ['version', '8c30575264b2772c7a69c5bb6069a28f0e0a7a0df735871bde2d99ee674316ac'],
    properties => ['PropertyAliases.txt', '4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb'],
    values => ['PropValueAliases.txt', '670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01'],
    InPC => ['IndicPositionalCategory.txt', '68cedc29a7e57f984d90fe2c7712f2e6d0c717e253db219607daea8997d6c480'],
    InSC => ['IndicSyllabicCategory.txt', '3fc122f4cf58b0c19268d5f810263b04ab4e1e67743386ec0e0ada9c76aec5be'],
);

sub read_pinned {
    my ($key) = @_;
    my ($relative, $expected_hash) = @{$input{$key}};
    my $path = File::Spec->catfile($ucd, split m{/}, $relative);
    open my $fh, '<:raw', $path or die "Can't read $path: $!\n";
    local $/;
    my $bytes = <$fh>;
    close $fh or die "Can't close $path: $!\n";
    my $actual_hash = sha256_hex($bytes);
    die "$relative SHA-256 mismatch: $actual_hash\n"
        unless $actual_hash eq $expected_hash;
    return $bytes;
}

my %bytes = map { $_ => read_pinned($_) } keys %input;
(my $version = $bytes{version}) =~ s/\r?\n\z//;
die "Expected Unicode $expected_version, found $version\n"
    unless $version eq $expected_version;

for my $key (qw(properties values InPC InSC)) {
    my $source = $bytes{$key};
    die "$input{$key}[0] has the wrong Unicode version\n"
        unless $source =~ /(?:-|Version\s+)$expected_version(?:\.txt)?/;
    die "$input{$key}[0] is missing the Unicode copyright notice\n"
        unless $source =~ /\x{c2}\x{a9} 2025 Unicode\x{c2}\x{ae}, Inc\./;
    die "$input{$key}[0] is missing the Unicode trademark notice\n"
        unless $source =~ /Unicode and the Unicode Logo are registered trademarks/;
    die "$input{$key}[0] is missing the Unicode terms URL\n"
        unless $source =~ m{https://www\.unicode\.org/terms_of_use\.html};
}

my @property_specs = (
    {
        short => 'InPC', long => 'Indic_Positional_Category',
        default => 'Not_Applicable', records => 653, ranges => 884,
        explicit_cardinality => 1299, values => 16, aliases => 17,
    },
    {
        short => 'InSC', long => 'Indic_Syllabic_Category',
        default => 'Other', records => 967, ranges => 1154,
        explicit_cardinality => 4856, values => 37, aliases => 37,
    },
);

my %property_aliases;
for my $line (split /\n/, $bytes{properties}) {
    $line =~ s/#.*//;
    my @field = map { s/^\s+|\s+$//gr } split /;/, $line;
    next unless @field >= 2 && ($field[0] eq 'InPC' || $field[0] eq 'InSC');
    $property_aliases{$field[0]} = [@field[0, 1]];
}

sub loose {
    my ($text) = @_;
    $text =~ s/[ _\-\x09-\x0d]//g;
    $text =~ tr/A-Z/a-z/;
    return $text;
}

my (%values, %value_aliases);
for my $line (split /\n/, $bytes{values}) {
    $line =~ s/#.*//;
    my @field = map { s/^\s+|\s+$//gr } split /;/, $line;
    next unless @field >= 3 && ($field[0] eq 'InPC' || $field[0] eq 'InSC');
    my ($property, $short, $long, @extra) = @field;
    push @{$values{$property}}, [$short, $long];
    my $id = $#{$values{$property}};
    for my $alias ($short, $long, grep { length } @extra) {
        my $key = loose($alias);
        die "Conflicting $property value alias $alias\n"
            if exists $value_aliases{$property}{$key}
                && $value_aliases{$property}{$key} != $id;
        $value_aliases{$property}{$key} = $id;
    }
}

sub parse_data {
    my ($spec) = @_;
    my $property = $spec->{short};
    my %value_id = map { $values{$property}[$_][1] => $_ }
        0 .. $#{$values{$property}};
    die "Missing default value $spec->{default} for $property\n"
        unless exists $value_id{$spec->{default}};

    my @records;
    my $missing;
    for my $line (split /\n/, $bytes{$property}) {
        if ($line =~ /^#\s*\@missing:\s*0000\.\.10FFFF;\s*(\S+)/) {
            $missing = $1;
            next;
        }
        $line =~ s/#.*//;
        next unless $line =~ /^\s*([0-9A-F]+)(?:\.\.([0-9A-F]+))?\s*;\s*(\S+)\s*$/;
        my ($start, $end, $value) = (hex($1), defined($2) ? hex($2) : hex($1), $3);
        die "Unknown $property value $value\n" unless exists $value_id{$value};
        die "Invalid $property range in $line\n"
            if $start > $end || $end > 0x10ffff;
        push @records, [$start, $end, $value_id{$value}];
    }
    die "$property default mismatch\n"
        unless defined $missing && $missing eq $spec->{default};
    die "$property record count mismatch: " . scalar(@records) . "\n"
        unless @records == $spec->{records};

    @records = sort { $a->[0] <=> $b->[0] || $a->[1] <=> $b->[1] } @records;
    my $explicit_cardinality = 0;
    for my $i (0 .. $#records) {
        die "$property overlapping ranges at U+" . sprintf('%04X', $records[$i][0]) . "\n"
            if $i && $records[$i][0] <= $records[$i - 1][1];
        $explicit_cardinality += $records[$i][1] - $records[$i][0] + 1;
    }
    die "$property explicit cardinality mismatch: $explicit_cardinality\n"
        unless $explicit_cardinality == $spec->{explicit_cardinality};

    my $default_id = $value_id{$spec->{default}};
    my @complete;
    my $next = 0;
    for my $record (@records) {
        push @complete, [$next, $record->[0] - 1, $default_id]
            if $next < $record->[0];
        push @complete, [@$record];
        $next = $record->[1] + 1;
    }
    push @complete, [$next, 0x10ffff, $default_id] if $next <= 0x10ffff;

    my @merged;
    for my $range (@complete) {
        if (@merged && $merged[-1][2] == $range->[2]
                && $merged[-1][1] + 1 == $range->[0]) {
            $merged[-1][1] = $range->[1];
        } else {
            push @merged, [@$range];
        }
    }
    die "$property complete range count mismatch: " . scalar(@merged) . "\n"
        unless @merged == $spec->{ranges};
    die "$property partition boundary mismatch\n"
        unless $merged[0][0] == 0 && $merged[-1][1] == 0x10ffff;

    my @cardinality = (0) x scalar(@{$values{$property}});
    for my $range (@merged) {
        $cardinality[$range->[2]] += $range->[1] - $range->[0] + 1;
    }
    my $total = 0; $total += $_ for @cardinality;
    die "$property partition cardinality mismatch: $total\n" unless $total == 0x110000;
    return (\@merged, \@cardinality);
}

for my $spec (@property_specs) {
    my $aliases = $property_aliases{$spec->{short}};
    die "Missing property aliases for $spec->{short}\n"
        unless $aliases && $aliases->[0] eq $spec->{short}
            && $aliases->[1] eq $spec->{long};
    die "$spec->{short} value count mismatch\n"
        unless @{$values{$spec->{short}}} == $spec->{values};
    die "$spec->{short} alias count mismatch\n"
        unless keys(%{$value_aliases{$spec->{short}}}) == $spec->{aliases};
    ($spec->{range_data}, $spec->{cardinality}) = parse_data($spec);
}

sub java_strings {
    return join(', ', map { my $s = $_; $s =~ s/([\\"])/\\$1/g; qq{"$s"} } @_);
}

print <<'HEADER';
/*
 * Generated from Perl 5.44's Unicode 17.0.0 Character Database.
 * Do not edit manually; run dev/tools/generate_perl_unicode_indic_category_data.pl.
 *
 * Unicode data source copyright:
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in
 * the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;
import java.util.Arrays;

final class PerlUnicodeIndicCategoryData {
HEADER

print qq{    static final String UNICODE_VERSION = "$version";\n};
print qq{    static final String PROPERTY_ALIASES_SHA256 = "$input{properties}[1]";\n};
print qq{    static final String VALUE_ALIASES_SHA256 = "$input{values}[1]";\n};
print qq{    static final String INDIC_POSITIONAL_SHA256 = "$input{InPC}[1]";\n};
print qq{    static final String INDIC_SYLLABIC_SHA256 = "$input{InSC}[1]";\n\n};
print '    private static final String[] PROPERTY_SHORT_NAMES = {'
    . java_strings(map { $_->{short} } @property_specs) . "};\n";
print '    private static final String[] PROPERTY_NAMES = {'
    . java_strings(map { $_->{long} } @property_specs) . "};\n";

for my $p (0 .. $#property_specs) {
    my $spec = $property_specs[$p];
    my $property = $spec->{short};
    print "\n    private static final String[] SHORT_VALUES_$p = {\n        ";
    print java_strings(map { $_->[0] } @{$values{$property}}), "\n    };\n";
    print "    private static final String[] VALUES_$p = {\n        ";
    print java_strings(map { $_->[1] } @{$values{$property}}), "\n    };\n";
    my @keys = sort keys %{$value_aliases{$property}};
    print "    private static final String[] ALIAS_KEYS_$p = {\n        ",
        java_strings(@keys), "\n    };\n";
    print "    private static final byte[] ALIAS_IDS_$p = {\n        ",
        join(', ', map { $value_aliases{$property}{$_} } @keys), "\n    };\n";
    print "    private static final int[] CARDINALITIES_$p = {\n        ",
        join(', ', @{$spec->{cardinality}}), "\n    };\n";
    print "    private static final int[] RANGES_$p = {\n";
    my $ranges = $spec->{range_data};
    for (my $i = 0; $i < @$ranges; $i += 4) {
        my $end = $i + 3 < $#$ranges ? $i + 3 : $#$ranges;
        print '        ', join(', ', map {
            sprintf '0x%X, 0x%X, %d', @{$ranges->[$_]}[0, 1, 2]
        } $i .. $end), ",\n";
    }
    print "    };\n";
}

print <<'FOOTER';

    private static final String[][] SHORT_VALUES = {SHORT_VALUES_0, SHORT_VALUES_1};
    private static final String[][] VALUES = {VALUES_0, VALUES_1};
    private static final String[][] ALIAS_KEYS = {ALIAS_KEYS_0, ALIAS_KEYS_1};
    private static final byte[][] ALIAS_IDS = {ALIAS_IDS_0, ALIAS_IDS_1};
    private static final int[][] CARDINALITIES = {CARDINALITIES_0, CARDINALITIES_1};
    private static final int[][] RANGES = {RANGES_0, RANGES_1};
    private static final UnicodeSet[][] SETS = buildSets();

    static boolean isPropertyAlias(String alias) {
        return propertyIndex(alias) >= 0;
    }

    static String canonicalProperty(String alias) {
        int property = propertyIndex(alias);
        return property < 0 ? null : PROPERTY_NAMES[property];
    }

    static UnicodeSet valueSet(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        int value = property < 0 ? -1 : valueIndex(property, valueAlias);
        return value < 0 ? null : SETS[property][value];
    }

    static String canonicalValue(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        int value = property < 0 ? -1 : valueIndex(property, valueAlias);
        return value < 0 ? null : VALUES[property][value];
    }

    static String shortValue(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        int value = property < 0 ? -1 : valueIndex(property, valueAlias);
        return value < 0 ? null : SHORT_VALUES[property][value];
    }

    static String[] canonicalValues(String propertyAlias) {
        int property = propertyIndex(propertyAlias);
        return property < 0 ? null : VALUES[property].clone();
    }

    static String[] wildcardValues(String propertyAlias) {
        return canonicalValues(propertyAlias);
    }

    static int rangeCount(String propertyAlias) {
        int property = propertyIndex(propertyAlias);
        return property < 0 ? -1 : RANGES[property].length / 3;
    }

    static int expectedCardinality(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        int value = property < 0 ? -1 : valueIndex(property, valueAlias);
        return value < 0 ? -1 : CARDINALITIES[property][value];
    }

    private static int propertyIndex(String alias) {
        String key = loose(alias);
        if (key == null) return -1;
        for (int i = 0; i < PROPERTY_NAMES.length; i++) {
            if (key.equals(loose(PROPERTY_SHORT_NAMES[i]))
                    || key.equals(loose(PROPERTY_NAMES[i]))) return i;
        }
        return -1;
    }

    private static int valueIndex(int property, String alias) {
        String key = loose(alias);
        if (key == null) return -1;
        int index = Arrays.binarySearch(ALIAS_KEYS[property], key);
        return index < 0 ? -1 : ALIAS_IDS[property][index] & 0xff;
    }

    private static String loose(String value) {
        if (value == null) return null;
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '_' || c == '-' || (c >= '\t' && c <= '\r')) continue;
            if (c >= 'A' && c <= 'Z') c = (char) (c + ('a' - 'A'));
            result.append(c);
        }
        return result.toString();
    }

    private static UnicodeSet[][] buildSets() {
        UnicodeSet[][] sets = new UnicodeSet[RANGES.length][];
        for (int property = 0; property < RANGES.length; property++) {
            sets[property] = new UnicodeSet[VALUES[property].length];
            for (int value = 0; value < sets[property].length; value++) {
                sets[property][value] = new UnicodeSet();
            }
            int[] ranges = RANGES[property];
            for (int i = 0; i < ranges.length; i += 3) {
                sets[property][ranges[i + 2]].add(ranges[i], ranges[i + 1]);
            }
            for (UnicodeSet set : sets[property]) set.freeze();
        }
        return sets;
    }

    private PerlUnicodeIndicCategoryData() {
    }
}
FOOTER
