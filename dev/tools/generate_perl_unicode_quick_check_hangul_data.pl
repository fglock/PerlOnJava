#!/usr/bin/env perl
use strict;
use warnings;

use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin qw($Bin);
use MIME::Base64 qw(encode_base64);

binmode STDOUT, ':raw';

my $root = File::Spec->rel2abs(File::Spec->catdir($Bin, '..', '..'));
my $ucd = File::Spec->catdir($root, 'perl5', 'lib', 'unicore');
my $expected_version = '17.0.0';
my %input = (
    version => ['version', '8c30575264b2772c7a69c5bb6069a28f0e0a7a0df735871bde2d99ee674316ac'],
    properties => ['PropertyAliases.txt', '4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb'],
    values => ['PropValueAliases.txt', '670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01'],
    hst => ['HangulSyllableType.txt', '5a57450afde0d082bc5026f7458649eac3b615490cc7e3d916b0367f1593c0e3'],
    normalization => ['DNormalizationProps.txt', '71fd6a206a2c0cdd41feb6b7f656aa31091db45e9cedc926985d718397f9e488'],
);

sub read_pinned {
    my ($key) = @_;
    my ($relative, $expected_hash) = @{$input{$key}};
    my $path = File::Spec->catfile($ucd, $relative);
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

my %header = (
    properties => 'PropertyAliases-17.0.0.txt',
    values => 'PropertyValueAliases-17.0.0.txt',
    hst => 'HangulSyllableType-17.0.0.txt',
    normalization => 'DerivedNormalizationProps-17.0.0.txt',
);
for my $key (keys %header) {
    my $source = $bytes{$key};
    die "$input{$key}[0] has the wrong Unicode header\n"
        unless $source =~ /^# \Q$header{$key}\E$/m;
    die "$input{$key}[0] is missing the Unicode copyright notice\n"
        unless $source =~ /^# \x{c2}\x{a9} 2025 Unicode\x{c2}\x{ae}, Inc\.$/m;
    die "$input{$key}[0] is missing the Unicode trademark notice\n"
        unless $source =~ /^# Unicode and the Unicode Logo are registered trademarks/m;
    die "$input{$key}[0] is missing the Unicode terms URL\n"
        unless $source =~ m{^# For terms of use and license, see https://www\.unicode\.org/terms_of_use\.html$}m;
}

my @specs = (
    { short => 'hst', long => 'Hangul_Syllable_Type', source => 'hst',
      default => 'Not_Applicable', records => 804, ranges => 810,
      explicit => 11529, values => 6, aliases => 12 },
    { short => 'NFC_QC', long => 'NFC_Quick_Check', source => 'normalization',
      default => 'Yes', records => 124, ranges => 242,
      explicit => 1252, values => 3, aliases => 6 },
    { short => 'NFD_QC', long => 'NFD_Quick_Check', source => 'normalization',
      default => 'Yes', records => 253, ranges => 485,
      explicit => 13253, values => 2, aliases => 4 },
    { short => 'NFKC_QC', long => 'NFKC_Quick_Check', source => 'normalization',
      default => 'Yes', records => 445, ranges => 607,
      explicit => 5097, values => 3, aliases => 6 },
    { short => 'NFKD_QC', long => 'NFKD_Quick_Check', source => 'normalization',
      default => 'Yes', records => 560, ranges => 817,
      explicit => 17086, values => 2, aliases => 4 },
);

sub trim {
    my ($text) = @_;
    $text =~ s/^\s+|\s+$//g;
    return $text;
}

sub loose {
    my ($text) = @_;
    $text =~ s/[ _\-\x09-\x0d]//g;
    $text =~ tr/A-Z/a-z/;
    return $text;
}

my %property_aliases;
for my $line (split /\n/, $bytes{properties}) {
    $line =~ s/#.*//;
    my @field = map { trim($_) } split /;/, $line, -1;
    next unless @field >= 2;
    for my $spec (@specs) {
        $property_aliases{$spec->{short}} = [@field[0, 1]]
            if $field[0] eq $spec->{short};
    }
}

my (%values, %aliases);
for my $line (split /\n/, $bytes{values}) {
    $line =~ s/#.*//;
    my @field = map { trim($_) } split /;/, $line, -1;
    next unless @field >= 3;
    my ($spec) = grep { $_->{short} eq $field[0] } @specs;
    next unless $spec;
    my ($short, $canonical, @extra) = @field[1 .. $#field];
    push @{$values{$spec->{short}}}, [$short, $canonical];
    my $id = $#{$values{$spec->{short}}};
    for my $alias (grep { length } ($short, $canonical, @extra)) {
        my $key = loose($alias);
        die "Conflicting $spec->{short} value alias $alias\n"
            if exists $aliases{$spec->{short}}{$key}
                && $aliases{$spec->{short}}{$key} != $id;
        $aliases{$spec->{short}}{$key} = $id;
    }
}

sub parse_range {
    my ($text) = @_;
    my ($first, $last) = split /\.\./, $text;
    return (hex($first), hex(defined($last) ? $last : $first));
}

sub parse_property_data {
    my ($spec) = @_;
    my $property = $spec->{short};
    my %id_for_alias = %{$aliases{$property}};
    my @records;
    my $missing;
    for my $line (split /\n/, $bytes{$spec->{source}}) {
        if ($spec->{source} eq 'hst') {
            if ($line =~ /^#\s*\@missing:\s*([0-9A-F.]+)\s*;\s*(\S+)/) {
                $missing = $2;
                next;
            }
            $line =~ s/#.*//;
            next unless $line =~ /^\s*([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*(\S+)\s*$/;
            my ($start, $end) = parse_range($1);
            push @records, [$start, $end, $2];
        } else {
            if ($line =~ /^#\s*\@missing:\s*[0-9A-F.]+\s*;\s*(\S+)\s*;\s*(\S+)/) {
                $missing = $2 if $1 eq $property;
                next;
            }
            $line =~ s/#.*//;
            next unless $line =~ /^\s*([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*(\S+)\s*;\s*(\S+)\s*$/;
            next unless $2 eq $property;
            my ($start, $end) = parse_range($1);
            push @records, [$start, $end, $3];
        }
    }

    die "$property default mismatch\n"
        unless defined($missing) && loose($missing) eq loose($spec->{default});
    die "$property record count mismatch: " . scalar(@records) . "\n"
        unless @records == $spec->{records};
    for my $record (@records) {
        my $key = loose($record->[2]);
        die "Unknown $property source value $record->[2]\n"
            unless exists $id_for_alias{$key};
        $record->[2] = $id_for_alias{$key};
        die "Invalid $property range\n"
            if $record->[0] > $record->[1] || $record->[1] > 0x10ffff;
    }
    @records = sort { $a->[0] <=> $b->[0] || $a->[1] <=> $b->[1] } @records;
    my $explicit = 0;
    for my $index (0 .. $#records) {
        die sprintf("Overlapping %s ranges at U+%04X\n", $property, $records[$index][0])
            if $index && $records[$index][0] <= $records[$index - 1][1];
        $explicit += $records[$index][1] - $records[$index][0] + 1;
    }
    die "$property explicit cardinality mismatch: $explicit\n"
        unless $explicit == $spec->{explicit};

    my $default_key = loose($spec->{default});
    die "Unknown $property default $spec->{default}\n"
        unless exists $id_for_alias{$default_key};
    my $default_id = $id_for_alias{$default_key};
    my (@complete, $next);
    $next = 0;
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
    die "$property partition boundaries are incomplete\n"
        unless $merged[0][0] == 0 && $merged[-1][1] == 0x10ffff;

    my @cardinality = (0) x @{$values{$property}};
    for my $range (@merged) {
        $cardinality[$range->[2]] += $range->[1] - $range->[0] + 1;
    }
    my $total = 0; $total += $_ for @cardinality;
    die "$property partition cardinality mismatch: $total\n" unless $total == 0x110000;
    return (\@merged, \@cardinality);
}

sub uleb128 {
    my ($value) = @_;
    my $bytes = '';
    do {
        my $byte = $value & 0x7f;
        $value >>= 7;
        $byte |= 0x80 if $value;
        $bytes .= chr($byte);
    } while ($value);
    return $bytes;
}

for my $spec (@specs) {
    my $property = $spec->{short};
    my $property_alias = $property_aliases{$property};
    die "Missing property aliases for $property\n"
        unless $property_alias && $property_alias->[0] eq $property
            && $property_alias->[1] eq $spec->{long};
    die "$property value count mismatch\n"
        unless @{$values{$property}} == $spec->{values};
    die "$property alias count mismatch\n"
        unless keys(%{$aliases{$property}}) == $spec->{aliases};
    ($spec->{range_data}, $spec->{cardinality}) = parse_property_data($spec);
    my $encoded = '';
    for my $range (@{$spec->{range_data}}) {
        $encoded .= uleb128($range->[1] - $range->[0]);
        $encoded .= uleb128($range->[2]);
    }
    $spec->{encoded} = encode_base64($encoded, '');
}

sub java_strings {
    return join(', ', map { my $s = $_; $s =~ s/([\\"])/\\$1/g; qq{"$s"} } @_);
}

sub print_blob {
    my ($name, $blob) = @_;
    my @parts = $blob =~ /.{1,100}/g;
    print "    private static final String $name =\n";
    for my $index (0 .. $#parts) {
        print '        "', $parts[$index], '"',
            ($index == $#parts ? ";\n" : " +\n");
    }
}

print <<'HEADER';
/*
 * Generated from Perl 5.44's Unicode 17.0.0 Character Database.
 * Do not edit manually; run
 * dev/tools/generate_perl_unicode_quick_check_hangul_data.pl.
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
import java.util.Base64;

final class PerlUnicodeQuickCheckHangulData {
HEADER

print qq{    static final String UNICODE_VERSION = "$version";\n};
print qq{    static final String PROPERTY_ALIASES_SHA256 = "$input{properties}[1]";\n};
print qq{    static final String VALUE_ALIASES_SHA256 = "$input{values}[1]";\n};
print qq{    static final String HANGUL_SYLLABLE_TYPE_SHA256 = "$input{hst}[1]";\n};
print qq{    static final String NORMALIZATION_PROPS_SHA256 = "$input{normalization}[1]";\n\n};
print '    private static final String[] PROPERTY_SHORT_NAMES = {'
    . java_strings(map { $_->{short} } @specs) . "};\n";
print '    private static final String[] PROPERTY_NAMES = {'
    . java_strings(map { $_->{long} } @specs) . "};\n";
print '    private static final int[] RANGE_COUNTS = {'
    . join(', ', map { $_->{ranges} } @specs) . "};\n";

for my $property_index (0 .. $#specs) {
    my $spec = $specs[$property_index];
    my $property = $spec->{short};
    print "\n    private static final String[] SHORT_VALUES_$property_index = {\n        ",
        java_strings(map { $_->[0] } @{$values{$property}}), "\n    };\n";
    print "    private static final String[] VALUES_$property_index = {\n        ",
        java_strings(map { $_->[1] } @{$values{$property}}), "\n    };\n";
    my @keys = sort keys %{$aliases{$property}};
    print "    private static final String[] ALIAS_KEYS_$property_index = {\n        ",
        java_strings(@keys), "\n    };\n";
    print "    private static final byte[] ALIAS_IDS_$property_index = {\n        ",
        join(', ', map { $aliases{$property}{$_} } @keys), "\n    };\n";
    print "    private static final int[] CARDINALITIES_$property_index = {\n        ",
        join(', ', @{$spec->{cardinality}}), "\n    };\n";
    print_blob("RANGE_DATA_$property_index", $spec->{encoded});
}

print <<'FOOTER';

    private static final String[][] SHORT_VALUES = {
        SHORT_VALUES_0, SHORT_VALUES_1, SHORT_VALUES_2, SHORT_VALUES_3, SHORT_VALUES_4
    };
    private static final String[][] VALUES = {
        VALUES_0, VALUES_1, VALUES_2, VALUES_3, VALUES_4
    };
    private static final String[][] ALIAS_KEYS = {
        ALIAS_KEYS_0, ALIAS_KEYS_1, ALIAS_KEYS_2, ALIAS_KEYS_3, ALIAS_KEYS_4
    };
    private static final byte[][] ALIAS_IDS = {
        ALIAS_IDS_0, ALIAS_IDS_1, ALIAS_IDS_2, ALIAS_IDS_3, ALIAS_IDS_4
    };
    private static final int[][] CARDINALITIES = {
        CARDINALITIES_0, CARDINALITIES_1, CARDINALITIES_2,
        CARDINALITIES_3, CARDINALITIES_4
    };
    private static final String[] RANGE_DATA = {
        RANGE_DATA_0, RANGE_DATA_1, RANGE_DATA_2, RANGE_DATA_3, RANGE_DATA_4
    };
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
        return property < 0 ? -1 : RANGE_COUNTS[property];
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
        UnicodeSet[][] sets = new UnicodeSet[RANGE_DATA.length][];
        for (int property = 0; property < RANGE_DATA.length; property++) {
            sets[property] = new UnicodeSet[VALUES[property].length];
            for (int value = 0; value < sets[property].length; value++) {
                sets[property][value] = new UnicodeSet();
            }
            byte[] data = Base64.getDecoder().decode(RANGE_DATA[property]);
            int[] cursor = {0};
            int start = 0;
            int ranges = 0;
            while (cursor[0] < data.length) {
                int end = start + readUleb128(data, cursor);
                int value = readUleb128(data, cursor);
                if (value < 0 || value >= sets[property].length || end > 0x10ffff) {
                    throw new IllegalStateException("Invalid generated Unicode range data");
                }
                sets[property][value].add(start, end);
                start = end + 1;
                ranges++;
            }
            if (start != 0x110000 || ranges != RANGE_COUNTS[property]) {
                throw new IllegalStateException("Incomplete generated Unicode partition");
            }
            for (UnicodeSet set : sets[property]) set.freeze();
        }
        return sets;
    }

    private static int readUleb128(byte[] data, int[] cursor) {
        int value = 0;
        int shift = 0;
        while (true) {
            if (cursor[0] >= data.length || shift > 28) {
                throw new IllegalStateException("Invalid generated ULEB128 data");
            }
            int next = data[cursor[0]++] & 0xff;
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
            shift += 7;
        }
    }

    private PerlUnicodeQuickCheckHangulData() {
    }
}
FOOTER
