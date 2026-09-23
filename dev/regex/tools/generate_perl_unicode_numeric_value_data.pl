#!/usr/bin/env perl
use strict;
use warnings;
use Config;
use File::Spec;
use FindBin;
use lib File::Spec->catdir($FindBin::Bin, 'lib');
use PerlOnJava::UnicodeGenerator qw(
    loose_name parse_range perl_language_provenance read_pinned_source repo_root
    select_unicode_root trim verify_unicode_notice
);

binmode STDOUT, ':raw';

my $expected_version = '17.0.0';
my @required_sources = (
    'version', File::Spec->catfile('extracted', 'DNumValues.txt'),
    'PropertyAliases.txt', 'PropValueAliases.txt',
);
my $root = repo_root($FindBin::Bin);
my $unicore = select_unicode_root(
    repo_root => $root, version => $expected_version,
    required => \@required_sources);
my $perl_version = perl_language_provenance(
    repo_root => $root, unicode_root => $unicore,
    unicode_version => $expected_version);
my %sources = (
    Version => [File::Spec->catfile($unicore, 'version'),
        '8c30575264b2772c7a69c5bb6069a28f0e0a7a0df735871bde2d99ee674316ac'],
    Numeric_Value => [File::Spec->catfile($unicore, 'extracted', 'DNumValues.txt'),
        '139b976bdc288be01c80f018523da769cf2845109b5a7f0f8a432db64bfedcfa'],
    Property_Aliases => [File::Spec->catfile($unicore, 'PropertyAliases.txt'),
        '4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb'],
    Property_Value_Aliases => [File::Spec->catfile($unicore, 'PropValueAliases.txt'),
        '670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01'],
);

die "Numeric_Value generation requires 64-bit Perl integers\n"
    unless $Config{ivsize} >= 8;

sub source_text {
    my ($name) = @_;
    my ($path, $expected_hash) = @{$sources{$name}};
    return ($path, read_pinned_source(path => $path, sha256 => $expected_hash));
}

sub loose { return loose_name(@_); }
sub range_from_text { return parse_range(@_); }

sub gcd {
    my ($left, $right) = @_;
    $left = -$left if $left < 0;
    while ($right != 0) {
        ($left, $right) = ($right, $left % $right);
    }
    return $left;
}

my ($version_path, $version_text) = source_text('Version');
$version_text =~ s/\s+\z//;
die "Expected Unicode $expected_version, found '$version_text' in $version_path\n"
    unless $version_text eq $expected_version;

my ($property_path, $property_text) = source_text('Property_Aliases');
die "$property_path is not pinned Unicode $expected_version data\n"
    unless $property_text =~ /^# PropertyAliases-\Q$expected_version\E\.txt$/m;
verify_unicode_notice($property_path, $property_text);
my @property_aliases;
for my $line (split /\n/, $property_text) {
    $line =~ s/#.*//;
    my @fields = map { trim($_) } split /;/, $line, -1;
    next unless @fields >= 2 && $fields[0] eq 'nv';
    @property_aliases = grep { length } @fields;
}
die "Missing nv aliases in $property_path\n" unless @property_aliases;
die "Unexpected nv aliases in $property_path: @property_aliases\n"
    unless join("\0", @property_aliases) eq join("\0", 'nv', 'Numeric_Value');

my ($value_path, $value_text) = source_text('Property_Value_Aliases');
die "$value_path is not pinned Unicode $expected_version data\n"
    unless $value_text =~ /^# PropertyValueAliases-\Q$expected_version\E\.txt$/m;
verify_unicode_notice($value_path, $value_text);
my @missing;
for my $line (split /\n/, $value_text) {
    if ($line =~ /^\#\s*\@missing:\s*([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;
            \s*Numeric_Value\s*;\s*([A-Za-z0-9_]+)/x) {
        my ($first, $last) = range_from_text($1);
        push @missing, [$first, $last, $2];
    }
}
die "Expected one Numeric_Value \@missing rule\n"
    unless @missing == 1 && $missing[0][0] == 0 && $missing[0][1] == 0x10ffff
        && $missing[0][2] eq 'NaN';

my ($data_path, $data_text) = source_text('Numeric_Value');
die "$data_path is not pinned Unicode $expected_version data\n"
    unless $data_text =~ /^# DerivedNumericValues-\Q$expected_version\E\.txt$/m;
verify_unicode_notice($data_path, $data_text);

my (@ranges, @values, %value_index, %decimal_value, %source_counts);
my ($record_count, $range_record_count, $explicit_count) = (0, 0, 0);
for my $line (split /\n/, $data_text) {
    next if $line =~ /^\s*(?:#|$)/;
    die "Malformed Numeric_Value record '$line'\n"
        unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;
                \s*([^;]+?)\s*;\s*;\s*(-?\d+(?:\/\d+)?)\s*(?:\#.*)?$/x;
    my ($range_text, $decimal, $canonical) = ($1, trim($2), $3);
    my ($first, $last) = range_from_text($range_text);
    my ($numerator, $denominator) = $canonical =~ m{^(-?\d+)(?:/(\d+))?$};
    $denominator = 1 unless defined $denominator;
    die "Invalid rational '$canonical'\n"
        if $denominator <= 0 || gcd($numerator, $denominator) != 1;
    die "Numeric value does not fit signed long: '$canonical'\n"
        if $numerator < -9_223_372_036_854_775_807
            || $numerator > 9_223_372_036_854_775_807;
    die "Conflicting decimal spelling '$decimal'\n"
        if exists $decimal_value{$decimal} && $decimal_value{$decimal} ne $canonical;
    $decimal_value{$decimal} = $canonical;

    if (!exists $value_index{$canonical}) {
        $value_index{$canonical} = scalar @values;
        my $perl_decimal_alias = sprintf '%.3e', $numerator / $denominator;
        push @values, [
            $canonical, 0 + $numerator, 0 + $denominator, $perl_decimal_alias
        ];
    }
    push @ranges, [$first, $last, $value_index{$canonical}];
    $record_count++;
    $range_record_count++ if $range_text =~ /\.\./;
    $explicit_count += $last - $first + 1;
    $source_counts{$canonical} += $last - $first + 1;
}

die "Expected 1,980 Numeric_Value records, found $record_count\n"
    unless $record_count == 1_980;
die "Expected 25 source range records, found $range_record_count\n"
    unless $range_record_count == 25;
die "Expected 2,023 numeric code points, found $explicit_count\n"
    unless $explicit_count == 2_023;
die "Expected 144 distinct numeric values, found " . scalar(@values) . "\n"
    unless @values == 144;
die "Expected 144 distinct decimal spellings, found " . scalar(keys %decimal_value) . "\n"
    unless keys(%decimal_value) == 144;
my %decimal_canonical = map { $decimal_value{$_} => 1 } keys %decimal_value;
die "Decimal spellings do not map one-to-one to the 144 exact values\n"
    unless keys(%decimal_canonical) == 144;
my %perl_decimal_for = map { $_->[0] => $_->[3] } @values;
for my $expected (
    ['1/12', '8.333e-02'], ['1/64', '1.562e-02'],
    ['1/7', '1.429e-01'], ['1/6', '1.667e-01'], ['3/64', '4.688e-02'],
) {
    die "Unexpected Perl decimal alias for $expected->[0]: "
            . ($perl_decimal_for{$expected->[0]} // '<missing>') . "\n"
        unless ($perl_decimal_for{$expected->[0]} // '') eq $expected->[1];
}

@ranges = sort { $a->[0] <=> $b->[0] } @ranges;
for my $index (0 .. $#ranges) {
    my $range = $ranges[$index];
    die sprintf("Invalid Numeric_Value range U+%04X..U+%04X\n", @$range[0, 1])
        if $range->[0] < 0 || $range->[1] > 0x10ffff || $range->[0] > $range->[1];
    die sprintf("Overlapping Numeric_Value ranges at U+%04X\n", $range->[0])
        if $index > 0 && $range->[0] <= $ranges[$index - 1][1];
}

my @coalesced;
for my $range (@ranges) {
    if (@coalesced && $coalesced[-1][2] == $range->[2]
            && $coalesced[-1][1] + 1 == $range->[0]) {
        $coalesced[-1][1] = $range->[1];
    } else {
        push @coalesced, [@$range];
    }
}
die "Expected 1,979 coalesced numeric ranges, found " . scalar(@coalesced) . "\n"
    unless @coalesced == 1_979;
die "Expected 1,112,089 NaN code points\n"
    unless 0x110000 - $explicit_count == 1_112_089;

my @ranges_by_value = map { [] } @values;
for my $range (@coalesced) {
    push @{$ranges_by_value[$range->[2]]}, [$range->[0], $range->[1]];
}
for my $index (0 .. $#values) {
    my $count = 0;
    $count += $_->[1] - $_->[0] + 1 for @{$ranges_by_value[$index]};
    die "Cardinality changed for numeric value '$values[$index][0]'\n"
        unless $count == $source_counts{$values[$index][0]};
}

my (@range_offsets, @range_endpoints);
for my $ranges_for_value (@ranges_by_value) {
    push @range_offsets, scalar(@range_endpoints) / 2;
    push @range_endpoints, @$_ for @$ranges_for_value;
}
push @range_offsets, scalar(@range_endpoints) / 2;

print <<'HEADER';
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/*
HEADER
print " * Generated from hash-verified Unicode Character Database sources in the\n";
print " * selected current Perl $perl_version checkout by\n";
print <<'HEADER';
 * dev/regex/tools/generate_perl_unicode_numeric_value_data.pl. Do not edit manually.
 *
 * Unicode data source copyright:
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in
 * the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 */
final class PerlUnicodeNumericValueData {
HEADER

print "    static final String UNICODE_VERSION = \"$expected_version\";\n";
print "    static final short INVALID = -1;\n\n";
print "    private static final String[] CANONICAL_VALUES = {\n        ";
print join(', ', map { qq{"$_->[0]"} } @values);
print "\n    };\n\n";
print "    private static final String[] DECIMAL_ALIASES = {\n        ";
print join(', ', map { qq{"$_->[3]"} } @values);
print "\n    };\n\n";
print "    private static final long[] NUMERATORS = {\n        ";
print join(', ', map { "$_->[1]L" } @values);
print "\n    };\n\n";
print "    private static final short[] DENOMINATORS = {\n        ";
print join(', ', map { $_->[2] } @values);
print "\n    };\n\n";
print "    private static final int[] RANGE_OFFSETS = {\n";
for (my $i = 0; $i < @range_offsets; $i += 16) {
    my $end = $i + 15 < $#range_offsets ? $i + 15 : $#range_offsets;
    print "        ", join(', ', @range_offsets[$i .. $end]), ",\n";
}
print "    };\n\n    private static final int[] RANGE_ENDPOINTS = {\n";
for (my $i = 0; $i < @range_endpoints; $i += 10) {
    my $end = $i + 9 < $#range_endpoints ? $i + 9 : $#range_endpoints;
    print "        ", join(', ', map { sprintf '0x%X', $range_endpoints[$_] } $i .. $end), ",\n";
}
print <<'FOOTER';
    };

    private static final MathContext PERL_NUMERIC_CONTEXT =
            new MathContext(4, RoundingMode.HALF_EVEN);
    private static final UnicodeSet[] SETS = buildSets();
    private static final BigDecimal[] DECIMALS = buildDecimals();
    private static final BigDecimal[] ALTERNATE_DECIMALS = buildAlternateDecimals();
    private static final UnicodeSet ASSIGNED = buildAssignedSet();
    private static final UnicodeSet NAN = new UnicodeSet(0, 0x10ffff)
            .removeAll(ASSIGNED).freeze();

    static int valueCount() {
        return CANONICAL_VALUES.length;
    }

    static String canonicalValue(int index) {
        return CANONICAL_VALUES[index];
    }

    static long numerator(int index) {
        return NUMERATORS[index];
    }

    static short valueForDecimal(BigDecimal decimal) {
        double binaryValue = decimal.doubleValue();
        if (!Double.isFinite(binaryValue)) return INVALID;
        BigDecimal canonical = new BigDecimal(binaryValue).round(PERL_NUMERIC_CONTEXT);
        for (short index = 0; index < DECIMALS.length; index++) {
            if (DENOMINATORS[index] != 1
                    && (DECIMALS[index].compareTo(canonical) == 0
                        || ALTERNATE_DECIMALS[index].compareTo(canonical) == 0)) {
                return index;
            }
        }
        return INVALID;
    }

    static int denominator(int index) {
        return DENOMINATORS[index];
    }

    static UnicodeSet set(int index) {
        return SETS[index];
    }

    static UnicodeSet nanSet() {
        return NAN;
    }

    static UnicodeSet assignedSet() {
        return ASSIGNED;
    }

    static short valueForRational(long numerator, long denominator) {
        if (denominator == 0 || numerator == Long.MIN_VALUE || denominator == Long.MIN_VALUE) {
            return INVALID;
        }
        if (denominator < 0) {
            numerator = -numerator;
            denominator = -denominator;
        }
        long divisor = gcd(numerator, denominator);
        numerator /= divisor;
        denominator /= divisor;
        for (short index = 0; index < CANONICAL_VALUES.length; index++) {
            if (NUMERATORS[index] == numerator && DENOMINATORS[index] == denominator) {
                return index;
            }
        }
        return INVALID;
    }

    static boolean isPropertyAlias(String alias) {
        boolean hasIsPrefix = alias != null && alias.startsWith("Is");
        String normalized = loose(hasIsPrefix ? alias.substring(2) : alias);
        switch (normalized) {
            case "numericvalue":
            case "nv":
                return true;
            default:
                return false;
        }
    }

    private static UnicodeSet[] buildSets() {
        UnicodeSet[] sets = new UnicodeSet[CANONICAL_VALUES.length];
        for (int value = 0; value < sets.length; value++) {
            UnicodeSet set = new UnicodeSet();
            for (int range = RANGE_OFFSETS[value]; range < RANGE_OFFSETS[value + 1]; range++) {
                set.add(RANGE_ENDPOINTS[range * 2], RANGE_ENDPOINTS[range * 2 + 1]);
            }
            sets[value] = set.freeze();
        }
        return sets;
    }

    private static BigDecimal[] buildDecimals() {
        BigDecimal[] decimals = new BigDecimal[DECIMAL_ALIASES.length];
        for (int index = 0; index < decimals.length; index++) {
            decimals[index] = new BigDecimal(DECIMAL_ALIASES[index]);
            if (index > 0 && decimals[index - 1].compareTo(decimals[index]) >= 0) {
                throw new IllegalStateException("Numeric_Value decimals are not sorted");
            }
        }
        return decimals;
    }

    private static BigDecimal[] buildAlternateDecimals() {
        MathContext alternateContext = new MathContext(4, RoundingMode.HALF_UP);
        BigDecimal[] decimals = new BigDecimal[NUMERATORS.length];
        for (int index = 0; index < decimals.length; index++) {
            decimals[index] = BigDecimal.valueOf(NUMERATORS[index])
                    .divide(BigDecimal.valueOf(DENOMINATORS[index]), alternateContext);
        }
        return decimals;
    }

    private static UnicodeSet buildAssignedSet() {
        UnicodeSet assigned = new UnicodeSet();
        for (UnicodeSet set : SETS) assigned.addAll(set);
        return assigned.freeze();
    }

    private static long gcd(long left, long right) {
        left = Math.abs(left);
        while (right != 0) {
            long remainder = left % right;
            left = right;
            right = remainder;
        }
        return left;
    }

    private static String loose(String alias) {
        if (alias == null) return "";
        StringBuilder normalized = new StringBuilder(alias.length());
        for (int i = 0; i < alias.length(); i++) {
            char character = alias.charAt(i);
            if (character == '_' || character == '-' || character == ' '
                    || (character >= '\t' && character <= '\r')) continue;
            normalized.append(Character.toLowerCase(character));
        }
        return normalized.toString();
    }

    private PerlUnicodeNumericValueData() {
    }
}
FOOTER
