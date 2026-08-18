#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin;
use MIME::Base64 qw(encode_base64);

binmode STDOUT, ':raw';

my $expected_version = '17.0.0';
my $unicore = File::Spec->catdir($FindBin::Bin, '..', '..', 'perl5', 'lib', 'unicore');
my %sources = (
    Version => [File::Spec->catfile($unicore, 'version'),
        '8c30575264b2772c7a69c5bb6069a28f0e0a7a0df735871bde2d99ee674316ac'],
    Property_Aliases => [File::Spec->catfile($unicore, 'PropertyAliases.txt'),
        '4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb'],
    Property_Value_Aliases => [File::Spec->catfile($unicore, 'PropValueAliases.txt'),
        '670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01'],
    PropList => [File::Spec->catfile($unicore, 'PropList.txt'),
        '130dcddcaadaf071008bdfce1e7743e04fdfbc910886f017d9f9ac931d8c64dd'],
    Derived_Core => [File::Spec->catfile($unicore, 'DCoreProperties.txt'),
        '24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08'],
    Derived_Binary => [File::Spec->catfile($unicore, 'extracted', 'DBinaryProperties.txt'),
        '13dd09d35a9377e33eb388a01e6581d4bfec6b2685316078c341982fa444071a'],
    Emoji => [File::Spec->catfile($unicore, 'emoji', 'emoji.txt'),
        '2cb2bb9455cda83e8481541ecf5b6dfda66a3bb89efa3fa7c5297eccf607b72b'],
    Normalization => [File::Spec->catfile($unicore, 'DNormalizationProps.txt'),
        '71fd6a206a2c0cdd41feb6b7f656aa31091db45e9cedc926985d718397f9e488'],
    Composition_Exclusions => [File::Spec->catfile($unicore, 'CompositionExclusions.txt'),
        '5e6e9c8f8e76561da04cb1703a9306c63707be2ed8ff2eb12cd3a942368a6f72'],
);

my @selected = qw(
    Alphabetic Bidi_Control Bidi_Mirrored Cased Composition_Exclusion
    Case_Ignorable Full_Composition_Exclusion Changes_When_Casefolded
    Changes_When_Casemapped Changes_When_NFKC_Casefolded
    Changes_When_Lowercased Changes_When_Titlecased Changes_When_Uppercased Dash
    Deprecated Default_Ignorable_Code_Point Diacritic Emoji_Modifier_Base
    Emoji_Component Emoji_Modifier Emoji Emoji_Presentation Extender
    Grapheme_Base Grapheme_Extend Hex_Digit ID_Continue Ideographic ID_Start
    IDS_Binary_Operator IDS_Trinary_Operator Join_Control
    Logical_Order_Exception Lowercase Math Noncharacter_Code_Point
    Pattern_Syntax Pattern_White_Space Prepended_Concatenation_Mark
    Quotation_Mark Radical Regional_Indicator Soft_Dotted Sentence_Terminal
    Terminal_Punctuation Unified_Ideograph Uppercase Variation_Selector
    White_Space XID_Continue XID_Start
);
my %selected = map { $_ => 1 } @selected;
die "Expected 51 selected binary properties\n" unless @selected == 51;

sub source_text {
    my ($name) = @_;
    my ($path, $expected_hash) = @{$sources{$name}};
    open my $input, '<:raw', $path or die "Cannot read $path: $!\n";
    local $/;
    my $text = <$input>;
    close $input or die "Cannot close $path: $!\n";
    my $actual_hash = sha256_hex($text);
    die "$path SHA-256 mismatch: expected $expected_hash, found $actual_hash\n"
        unless $actual_hash eq $expected_hash;
    return ($path, $text);
}

sub trim {
    my ($text) = @_;
    $text =~ s/^\s+|\s+$//g;
    return $text;
}

sub loose {
    my ($text) = @_;
    $text =~ tr/A-Z/a-z/;
    $text =~ s/[\x09-\x0d _-]+//g;
    return $text;
}

sub verify_unicode_source {
    my ($path, $text, $header, $version_pattern) = @_;
    $version_pattern //= qr/^# \Q$header\E-\Q$expected_version\E\.txt$/m;
    die "$path is not pinned Unicode $expected_version $header data\n"
        unless $text =~ $version_pattern;
    die "$path does not preserve the Unicode copyright notice\n"
        unless $text =~ /^# © 2025 Unicode®, Inc\.$/m;
    die "$path does not preserve the Unicode trademark notice\n"
        unless $text =~ /^# Unicode and the Unicode Logo are registered trademarks of Unicode, Inc\. in the U\.S\. and other countries\.$/m;
    die "$path does not preserve the Unicode terms notice\n"
        unless $text =~ m{^# For terms of use and license, see https://www\.unicode\.org/terms_of_use\.html$}m;
}

sub range_from_match {
    my ($first, $last) = @_;
    return (hex($first), hex(defined $last ? $last : $first));
}

sub append_varint {
    my ($bytes, $value) = @_;
    die "Cannot encode negative varint $value\n" if $value < 0;
    while ($value >= 0x80) {
        push @$bytes, ($value & 0x7f) | 0x80;
        $value >>= 7;
    }
    push @$bytes, $value;
}

my ($version_path, $version_text) = source_text('Version');
$version_text =~ s/\s+\z//;
die "Expected Unicode $expected_version, found '$version_text' in $version_path\n"
    unless $version_text eq $expected_version;

my ($property_path, $property_text) = source_text('Property_Aliases');
verify_unicode_source($property_path, $property_text, 'PropertyAliases');
my (%canonical_aliases, %alias_property);
my $in_binary = 0;
for my $line (split /\n/, $property_text) {
    if ($line =~ /^# Binary Properties$/) {
        $in_binary = 1;
        next;
    }
    last if $in_binary && $line =~ /^# Total:/;
    next unless $in_binary;
    $line =~ s/#.*//;
    next unless $line =~ /;/;
    my @aliases = grep { length } map { trim($_) } split /;/, $line, -1;
    next unless @aliases >= 2 && $selected{$aliases[1]};
    my $canonical = $aliases[1];
    die "Duplicate property alias row for $canonical\n" if $canonical_aliases{$canonical};
    $canonical_aliases{$canonical} = \@aliases;
    for my $alias (@aliases) {
        my $key = loose($alias);
        die "Binary property alias '$alias' collides across properties\n"
            if exists $alias_property{$key} && $alias_property{$key} ne $canonical;
        $alias_property{$key} = $canonical;
    }
}
die "Expected aliases for all 51 selected properties\n"
    unless keys(%canonical_aliases) == @selected;
die "Expected 98 loose binary property aliases, found " . scalar(keys %alias_property) . "\n"
    unless keys(%alias_property) == 98;

my ($value_path, $value_text) = source_text('Property_Value_Aliases');
verify_unicode_source($value_path, $value_text, 'PropertyValueAliases');
my %boolean_rows;
my %short_selected = map { $canonical_aliases{$_}[0] => $_ } @selected;
for my $line (split /\n/, $value_text) {
    $line =~ s/#.*//;
    next unless $line =~ /;/;
    my @fields = grep { length } map { trim($_) } split /;/, $line, -1;
    next unless @fields && $short_selected{$fields[0]};
    push @{$boolean_rows{$fields[0]}}, [@fields[1 .. $#fields]];
}
for my $short (sort keys %short_selected) {
    my $rows = $boolean_rows{$short};
    die "Missing Boolean value aliases for $short\n" unless $rows && @$rows == 2;
    my @normalized = sort map { join("\0", @$_) } @$rows;
    die "Unexpected Boolean value aliases for $short\n"
        unless $normalized[0] eq join("\0", qw(N No F False))
            && $normalized[1] eq join("\0", qw(Y Yes T True));
}

my (%ranges, %owner);
my @range_sources = (
    ['PropList', 'PropList'],
    ['Derived_Core', 'DerivedCoreProperties'],
    ['Derived_Binary', 'DerivedBinaryProperties'],
    ['Emoji', 'emoji-data', qr/^# emoji-data\.txt\n(?:#.*\n)*?# Version: 17\.0$/m],
    ['Normalization', 'DerivedNormalizationProps'],
);
for my $source (@range_sources) {
    my ($name, $header, $version_pattern) = @$source;
    my ($path, $text) = source_text($name);
    verify_unicode_source($path, $text, $header, $version_pattern);
    for my $line (split /\n/, $text) {
        next if $line =~ /^\s*(?:#|$)/;
        next unless $line =~ /^([0-9A-F]+)(?:\.\.([0-9A-F]+))?\s*;
                \s*([A-Za-z_]+)/x;
        my ($first, $last) = range_from_match($1, $2);
        my $property = $3;
        next unless $selected{$property};
        push @{$ranges{$property}}, [$first, $last];
        $owner{$property}{$name} = 1;
    }
}

my ($composition_path, $composition_text) = source_text('Composition_Exclusions');
verify_unicode_source($composition_path, $composition_text, 'CompositionExclusions');
for my $line (split /\n/, $composition_text) {
    next if $line =~ /^\s*(?:#|$)/;
    die "Malformed CompositionExclusions record '$line'\n"
        unless $line =~ /^([0-9A-F]+)(?:\.\.([0-9A-F]+))?\s*(?:#.*)?$/;
    my ($first, $last) = range_from_match($1, $2);
    push @{$ranges{Composition_Exclusion}}, [$first, $last];
    $owner{Composition_Exclusion}{Composition_Exclusions} = 1;
}

my (%coalesced, %cardinality, %raw_count);
my %source_totals;
my ($total_raw, $total_merged, $total_cardinality) = (0, 0, 0);
for my $property (@selected) {
    die "No ranges for selected binary property $property\n" unless $ranges{$property};
    my @owners = keys %{$owner{$property}};
    die "Selected property $property has multiple source owners: @owners\n" unless @owners == 1;
    my @sorted = sort { $a->[0] <=> $b->[0] } @{$ranges{$property}};
    for my $index (0 .. $#sorted) {
        my ($first, $last) = @{$sorted[$index]};
        die sprintf("Invalid $property range U+%04X..U+%04X\n", $first, $last)
            if $first < 0 || $last > 0x10ffff || $first > $last;
        die sprintf("Overlapping $property ranges at U+%04X\n", $first)
            if $index > 0 && $first <= $sorted[$index - 1][1];
    }
    my @merged;
    for my $range (@sorted) {
        if (@merged && $merged[-1][1] + 1 == $range->[0]) {
            $merged[-1][1] = $range->[1];
        } else {
            push @merged, [@$range];
        }
    }
    my $count = 0;
    $count += $_->[1] - $_->[0] + 1 for @merged;
    $raw_count{$property} = scalar @sorted;
    $coalesced{$property} = \@merged;
    $cardinality{$property} = $count;
    $total_raw += @sorted;
    $total_merged += @merged;
    $total_cardinality += $count;
    my $source = $owners[0];
    $source_totals{$source}[0]++;
    $source_totals{$source}[1] += @sorted;
    $source_totals{$source}[2] += @merged;
    $source_totals{$source}[3] += $count;
}
die "Expected 15,918 raw ranges, found $total_raw\n" unless $total_raw == 15_918;
die "Expected 11,823 merged ranges, found $total_merged\n" unless $total_merged == 11_823;
die "Expected summed cardinality 1,158,810, found $total_cardinality\n"
    unless $total_cardinality == 1_158_810;
my %expected_source_totals = (
    PropList => [24, 975, 681, 218_417],
    Derived_Core => [18, 12_776, 9_791, 925_113],
    Emoji => [5, 775, 283, 2_942],
    Derived_Binary => [1, 224, 114, 554],
    Normalization => [2, 1_087, 921, 11_703],
    Composition_Exclusions => [1, 81, 33, 81],
);
for my $source (sort keys %expected_source_totals) {
    my $actual = join(',', @{$source_totals{$source} // []});
    my $expected = join(',', @{$expected_source_totals{$source}});
    die "Unexpected $source property/raw/merged/cardinality totals: "
        . "expected $expected, found $actual\n"
        unless $actual eq $expected;
}

my @encoded;
for my $property (@selected) {
    my @bytes;
    my $previous_end = -1;
    for my $range (@{$coalesced{$property}}) {
        append_varint(\@bytes, $range->[0] - $previous_end - 1);
        append_varint(\@bytes, $range->[1] - $range->[0]);
        $previous_end = $range->[1];
    }
    push @encoded, encode_base64(pack('C*', @bytes), '');
}

my %property_id = map { $selected[$_] => $_ } 0 .. $#selected;
my @alias_keys = sort keys %alias_property;

print <<'HEADER';
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;
import java.util.Arrays;
import java.util.Base64;

/*
 * Generated from Perl 5.44's pinned Unicode Character Database by
 * dev/tools/generate_perl_unicode_binary_property_data.pl. Do not edit manually.
 *
 * Unicode data source copyright:
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in
 * the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 */
final class PerlUnicodeBinaryPropertyData {
HEADER

print "    static final String UNICODE_VERSION = \"$expected_version\";\n";
print "    static final short INVALID = -1;\n";
print "    static final byte FALSE = 0;\n";
print "    static final byte TRUE = 1;\n\n";
print "    private static final String[] PROPERTY_NAMES = {\n";
for (my $i = 0; $i < @selected; $i += 5) {
    my $end = $i + 4 < $#selected ? $i + 4 : $#selected;
    print "        ", join(', ', map { qq{\"$selected[$_]\"} } $i .. $end), ",\n";
}
print "    };\n\n    private static final int[] RANGE_COUNTS = {\n        ";
print join(', ', map { scalar @{$coalesced{$_}} } @selected);
print "\n    };\n\n    private static final int[] CARDINALITIES = {\n        ";
print join(', ', map { $cardinality{$_} } @selected);
print "\n    };\n\n    private static final String[] RANGE_DATA = {\n";
for my $data (@encoded) {
    print "        \"$data\",\n";
}
print "    };\n\n    private static final String[] ALIAS_KEYS = {\n";
for (my $i = 0; $i < @alias_keys; $i += 6) {
    my $end = $i + 5 < $#alias_keys ? $i + 5 : $#alias_keys;
    print "        ", join(', ', map { qq{\"$alias_keys[$_]\"} } $i .. $end), ",\n";
}
print "    };\n\n    private static final short[] ALIAS_PROPERTY_IDS = {\n        ";
print join(', ', map { $property_id{$alias_property{$_}} } @alias_keys);
print <<'FOOTER';

    };

    private static final UnicodeSet[] SETS = buildSets();

    static int propertyCount() {
        return PROPERTY_NAMES.length;
    }

    static int aliasCount() {
        return ALIAS_KEYS.length;
    }

    static int totalRangeCount() {
        int total = 0;
        for (int count : RANGE_COUNTS) total += count;
        return total;
    }

    static String canonicalProperty(int propertyId) {
        return PROPERTY_NAMES[propertyId];
    }

    static int rangeCount(int propertyId) {
        return RANGE_COUNTS[propertyId];
    }

    static int cardinality(int propertyId) {
        return CARDINALITIES[propertyId];
    }

    static UnicodeSet set(int propertyId) {
        return SETS[propertyId];
    }

    static UnicodeSet set(String propertyAlias) {
        short propertyId = property(propertyAlias);
        return propertyId == INVALID ? null : SETS[propertyId];
    }

    static short property(String propertyAlias) {
        String key = loose(propertyAlias);
        int index = Arrays.binarySearch(ALIAS_KEYS, key);
        return index < 0 ? INVALID : ALIAS_PROPERTY_IDS[index];
    }

    static short assignmentProperty(String propertyAlias) {
        if (propertyAlias == null) return INVALID;
        String alias = propertyAlias.startsWith("Is")
                ? propertyAlias.substring(2) : propertyAlias;
        return property(alias);
    }

    static byte booleanValue(String valueAlias) {
        String value = loose(valueAlias);
        if (value.equals("y") || value.equals("yes")
                || value.equals("t") || value.equals("true")) return TRUE;
        if (value.equals("n") || value.equals("no")
                || value.equals("f") || value.equals("false")) return FALSE;
        return INVALID;
    }

    private static UnicodeSet[] buildSets() {
        UnicodeSet[] sets = new UnicodeSet[PROPERTY_NAMES.length];
        Base64.Decoder decoder = Base64.getDecoder();
        for (int propertyId = 0; propertyId < sets.length; propertyId++) {
            byte[] data = decoder.decode(RANGE_DATA[propertyId]);
            int[] offset = {0};
            int previousEnd = -1;
            UnicodeSet set = new UnicodeSet();
            while (offset[0] < data.length) {
                int gap = readVarint(data, offset);
                int length = readVarint(data, offset);
                int start = previousEnd + 1 + gap;
                int end = start + length;
                set.add(start, end);
                previousEnd = end;
            }
            if (set.getRangeCount() != RANGE_COUNTS[propertyId]
                    || set.size() != CARDINALITIES[propertyId]) {
                throw new IllegalStateException("Corrupt generated binary property data for "
                        + PROPERTY_NAMES[propertyId]);
            }
            sets[propertyId] = set.freeze();
        }
        return sets;
    }

    private static int readVarint(byte[] data, int[] offset) {
        int value = 0;
        int shift = 0;
        while (offset[0] < data.length) {
            int next = data[offset[0]++] & 0xff;
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
            shift += 7;
            if (shift > 28) throw new IllegalStateException("Invalid generated varint");
        }
        throw new IllegalStateException("Truncated generated varint");
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

    private PerlUnicodeBinaryPropertyData() {
    }
}
FOOTER
