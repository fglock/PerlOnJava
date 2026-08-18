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
    status => ['IdStatus.txt', '617228a16da13850bf8af28b6cd08f5e9b6595d2eb60404fe6eee2c85b4e4a35'],
    type => ['IdType.txt', '924ac63faa97ed73420d6ac48d08279d90968c7da0502ab701e08bfbb9683c22'],
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
for my $key (qw(status type)) {
    my $source = $bytes{$key};
    die "$input{$key}[0] has the wrong Unicode version\n"
        unless $source =~ /Version:\s+$expected_version/;
    die "$input{$key}[0] is missing the Unicode copyright notice\n"
        unless $source =~ /\x{c2}\x{a9} 2025 Unicode\x{c2}\x{ae}, Inc\./;
    die "$input{$key}[0] is missing the Unicode trademark notice\n"
        unless $source =~ /Unicode and the Unicode Logo are registered trademarks/;
    die "$input{$key}[0] is missing the Unicode terms URL\n"
        unless $source =~ m{https://www\.unicode\.org/terms_of_use\.html};
}

my @status_values = qw(Allowed Restricted);
my @type_values = qw(Not_Character Deprecated Default_Ignorable Not_NFKC Not_XID
    Exclusion Obsolete Technical Uncommon_Use Limited_Use Inclusion Recommended);
my %expected_cardinality = (
    Allowed => 33791,
    Deprecated => 15, Default_Ignorable => 398, Not_NFKC => 4958,
    Not_XID => 9113, Exclusion => 22048, Obsolete => 1941,
    Technical => 1864, Uncommon_Use => 83221, Limited_Use => 5285,
    Inclusion => 18, Recommended => 33773,
);
my %expected_ranges = (
    Allowed => 1612,
    Deprecated => 8, Default_Ignorable => 18, Not_NFKC => 257,
    Not_XID => 372, Exclusion => 260, Obsolete => 182,
    Technical => 145, Uncommon_Use => 1518, Limited_Use => 83,
    Inclusion => 14, Recommended => 1603,
);

sub merge_ranges {
    my (@ranges) = sort { $a->[0] <=> $b->[0] || $a->[1] <=> $b->[1] } @_;
    my @merged;
    for my $range (@ranges) {
        die 'Invalid code point range' if $range->[0] > $range->[1]
            || $range->[1] > 0x10ffff;
        if (@merged && $range->[0] <= $merged[-1][1] + 1) {
            $merged[-1][1] = $range->[1] if $range->[1] > $merged[-1][1];
        } else {
            push @merged, [@$range];
        }
    }
    return @merged;
}

sub parse_property {
    my ($key, $expected_default, $expected_records, $allowed_values) = @_;
    my %allowed = map { $_ => 1 } @$allowed_values;
    my (%ranges, @explicit);
    my ($default, $records);
    for my $line (split /\n/, $bytes{$key}) {
        $default = $1 if $line =~ /^#\s*\@missing:\s*0000\.\.10FFFF;\s*(\S+)/;
        $line =~ s/#.*//;
        next unless $line =~ /^\s*([0-9A-F]+)(?:\.\.([0-9A-F]+))?\s*;\s*(.*?)\s*$/;
        my ($start, $end, $values) =
            (hex($1), defined($2) ? hex($2) : hex($1), $3);
        die "Invalid $key range\n" if $start > $end || $end > 0x10ffff;
        my @values = split /\s+/, $values;
        die "$key record has no values\n" unless @values;
        my %seen;
        for my $value (@values) {
            die "Unknown $key value $value\n" unless $allowed{$value};
            die "Duplicate $key value $value\n" if $seen{$value}++;
            push @{$ranges{$value}}, [$start, $end];
        }
        push @explicit, [$start, $end];
        $records++;
    }
    die "$key default mismatch\n"
        unless defined($default) && $default eq $expected_default;
    die "$key record count mismatch: $records\n" unless $records == $expected_records;
    for my $value (keys %ranges) {
        @{$ranges{$value}} = merge_ranges(@{$ranges{$value}});
        my $cardinality = 0;
        $cardinality += $_->[1] - $_->[0] + 1 for @{$ranges{$value}};
        die "$key $value cardinality mismatch: $cardinality\n"
            unless $cardinality == $expected_cardinality{$value};
        die "$key $value range count mismatch: " . scalar(@{$ranges{$value}}) . "\n"
            unless @{$ranges{$value}} == $expected_ranges{$value};
    }
    return (\%ranges, [merge_ranges(@explicit)]);
}

my ($status_ranges, $status_explicit) =
    parse_property('status', 'Restricted', 1649, \@status_values);
my ($type_ranges, $type_explicit) =
    parse_property('type', 'Not_Character', 5104, \@type_values);
die "Identifier_Status contains an unexpected explicit value\n"
    unless keys(%$status_ranges) == 1 && exists $status_ranges->{Allowed};
die "Identifier_Type contains an unexpected explicit default\n"
    if exists $type_ranges->{Not_Character};

sub complement_ranges {
    my ($ranges) = @_;
    my (@result, $next);
    $next = 0;
    for my $range (@$ranges) {
        push @result, [$next, $range->[0] - 1] if $next < $range->[0];
        $next = $range->[1] + 1;
    }
    push @result, [$next, 0x10ffff] if $next <= 0x10ffff;
    return @result;
}

$status_ranges->{Restricted} = [complement_ranges($status_explicit)];
$type_ranges->{Not_Character} = [complement_ranges($type_explicit)];
$expected_cardinality{Restricted} = 0x110000 - $expected_cardinality{Allowed};
$expected_cardinality{Not_Character} = 954305;
$expected_ranges{Restricted} = scalar @{$status_ranges->{Restricted}};
$expected_ranges{Not_Character} = scalar @{$type_ranges->{Not_Character}};

for my $pair ([status => $status_ranges, \@status_values],
              [type => $type_ranges, \@type_values]) {
    my ($name, $ranges, $values) = @$pair;
    for my $value (@$values) {
        die "Missing $name value $value\n" unless exists $ranges->{$value};
    }
}

sub java_strings {
    return join(', ', map { my $s = $_; $s =~ s/([\\"])/\\$1/g; qq{"$s"} } @_);
}

sub set_pattern {
    my ($ranges) = @_;
    return '[' . join('', map {
        my ($start, $end) = @$_;
        my $text = sprintf('\\x{%X}', $start);
        $text .= sprintf('-\\x{%X}', $end) if $end != $start;
        $text;
    } @$ranges) . ']';
}

print <<'HEADER';
/*
 * Generated from Perl 5.44's Unicode 17.0.0 Character Database.
 * Do not edit manually; run dev/tools/generate_perl_unicode_identifier_data.pl.
 *
 * Unicode data source copyright:
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in
 * the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;

final class PerlUnicodeIdentifierData {
HEADER
print qq{    static final String UNICODE_VERSION = "$version";\n};
print qq{    static final String IDENTIFIER_STATUS_SHA256 = "$input{status}[1]";\n};
print qq{    static final String IDENTIFIER_TYPE_SHA256 = "$input{type}[1]";\n\n};
print "    private static final String[][] VALUES = {\n        {"
    . java_strings(@status_values) . "},\n        {" . java_strings(@type_values) . "}\n    };\n";
print "    private static final String[][] PATTERNS = {\n";
for my $spec ([status => \@status_values], [type => \@type_values]) {
    my ($key, $values) = @$spec;
    my $ranges = $key eq 'status' ? $status_ranges : $type_ranges;
    print "        {\n";
    for my $value (@$values) {
        print '            ', java_strings(set_pattern($ranges->{$value})), ",\n";
    }
    print "        },\n";
}
print "    };\n";
print "    private static final int[][] CARDINALITIES = {\n        {"
    . join(', ', map { $expected_cardinality{$_} } @status_values) . "},\n        {"
    . join(', ', map { $expected_cardinality{$_} } @type_values) . "}\n    };\n";
print "    private static final int[][] RANGE_COUNTS = {\n        {"
    . join(', ', map { $expected_ranges{$_} } @status_values) . "},\n        {"
    . join(', ', map { $expected_ranges{$_} } @type_values) . "}\n    };\n";

print <<'FOOTER';
    private static final UnicodeSet[][] SETS = buildSets();

    static boolean isPropertyAlias(String alias) {
        return propertyIndex(alias) >= 0;
    }

    static String canonicalProperty(String alias) {
        int property = propertyIndex(alias);
        return property == 0 ? "Identifier_Status"
                : property == 1 ? "Identifier_Type" : null;
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

    static String[] canonicalValues(String propertyAlias) {
        int property = propertyIndex(propertyAlias);
        return property < 0 ? null : VALUES[property].clone();
    }

    static String[] wildcardValues(String propertyAlias) {
        return canonicalValues(propertyAlias);
    }

    static int expectedCardinality(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        int value = property < 0 ? -1 : valueIndex(property, valueAlias);
        return value < 0 ? -1 : CARDINALITIES[property][value];
    }

    static int rangeCount(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        int value = property < 0 ? -1 : valueIndex(property, valueAlias);
        return value < 0 ? -1 : RANGE_COUNTS[property][value];
    }

    private static int propertyIndex(String alias) {
        String key = loose(alias);
        if ("idstatus".equals(key) || "identifierstatus".equals(key)) return 0;
        if ("idtype".equals(key) || "identifiertype".equals(key)) return 1;
        return -1;
    }

    private static int valueIndex(int property, String alias) {
        String key = loose(alias);
        if (key == null) return -1;
        for (int i = 0; i < VALUES[property].length; i++) {
            if (key.equals(loose(VALUES[property][i]))) return i;
        }
        return -1;
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
        UnicodeSet[][] sets = new UnicodeSet[PATTERNS.length][];
        for (int property = 0; property < PATTERNS.length; property++) {
            sets[property] = new UnicodeSet[PATTERNS[property].length];
            for (int value = 0; value < PATTERNS[property].length; value++) {
                sets[property][value] = new UnicodeSet(PATTERNS[property][value]).freeze();
            }
        }
        return sets;
    }

    private PerlUnicodeIdentifierData() {
    }
}
FOOTER
