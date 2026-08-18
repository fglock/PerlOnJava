#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin;

my $expected_version = '17.0.0';
my $root = File::Spec->catdir($FindBin::Bin, '..', '..');
my $unicore = File::Spec->catdir($root, 'perl5', 'lib', 'unicore');
my @properties = (
    {
        key => 'GCB', long => 'Grapheme_Cluster_Break', default => 'Other',
        source_name => 'GraphemeBreakProperty-17.0.0.txt',
        path => File::Spec->catfile($unicore, 'auxiliary', 'GraphemeBreakProperty.txt'),
        hash => 'd6b51d1d2ae5c33b451b7ed994b48f1f4dc62b2272a5831e7fd418514a6bae89',
        version => qr/^# GraphemeBreakProperty-\Q$expected_version\E\.txt$/m,
    },
    {
        key => 'SB', long => 'Sentence_Break', default => 'Other',
        source_name => 'SentenceBreakProperty-17.0.0.txt',
        path => File::Spec->catfile($unicore, 'auxiliary', 'SentenceBreakProperty.txt'),
        hash => '871c0c985ad95125e25b302414065a10839d068970bceb383ecec138f22a0a18',
        version => qr/^# SentenceBreakProperty-\Q$expected_version\E\.txt$/m,
    },
    {
        key => 'WB', long => 'Word_Break', default => 'Other',
        source_name => 'WordBreakProperty-17.0.0.txt',
        path => File::Spec->catfile($unicore, 'auxiliary', 'WordBreakProperty.txt'),
        hash => '72274cac1e6b919507db35655c3e175aa27274668a1ece95c28d2069f2ad9852',
        version => qr/^# WordBreakProperty-\Q$expected_version\E\.txt$/m,
    },
    {
        key => 'lb', long => 'Line_Break', default => 'Unknown',
        source_name => 'DerivedLineBreak-17.0.0.txt',
        path => File::Spec->catfile($unicore, 'extracted', 'DLineBreak.txt'),
        hash => 'dad3ef492d198d6f1dde4922b175f7371a27dfe62fce489f3e04807015a4c682',
        version => qr/^# DerivedLineBreak-\Q$expected_version\E\.txt$/m,
    },
);
my @metadata_sources = (
    {
        source_name => 'PropertyValueAliases-17.0.0.txt',
        path => File::Spec->catfile($unicore, 'PropValueAliases.txt'),
        hash => '670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01',
        version => qr/^# PropertyValueAliases-\Q$expected_version\E\.txt$/m,
    },
    {
        source_name => 'PropertyAliases-17.0.0.txt',
        path => File::Spec->catfile($unicore, 'PropertyAliases.txt'),
        hash => '4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb',
        version => qr/^# PropertyAliases-\Q$expected_version\E\.txt$/m,
    },
);

sub read_source {
    my ($source) = @_;
    open my $input, '<:raw', $source->{path}
        or die "Cannot read $source->{path}: $!\n";
    local $/;
    my $text = <$input>;
    close $input or die "Cannot close $source->{path}: $!\n";
    my $actual_hash = sha256_hex($text);
    die "$source->{path} SHA-256 mismatch: expected $source->{hash}, found $actual_hash\n"
        unless $actual_hash eq $source->{hash};
    die "$source->{path} is not pinned Unicode $expected_version data\n"
        unless $text =~ $source->{version};
    $source->{text} = $text;
}

sub trim {
    my ($text) = @_;
    $text =~ s/^\s+|\s+$//g;
    return $text;
}

sub loose_name {
    my ($name) = @_;
    $name = lc $name;
    $name =~ s/[\s_-]+//g;
    return $name;
}

sub parse_range {
    my ($text) = @_;
    my ($start, $end) = split /\.\./, $text;
    return (hex($start), hex(defined $end ? $end : $start));
}

read_source($_) for @properties, @metadata_sources;

my $version_path = File::Spec->catfile($unicore, 'version');
open my $version_input, '<', $version_path or die "Cannot read $version_path: $!\n";
chomp(my $unicode_version = <$version_input>);
close $version_input or die "Cannot close $version_path: $!\n";
die "Expected Unicode $expected_version, found $unicode_version\n"
    unless $unicode_version eq $expected_version;

my %by_key = map { $_->{key} => $_ } @properties;
for my $property (@properties) {
    $property->{short_values} = [];
    $property->{long_values} = [];
    $property->{alias_index} = {};
    $property->{wildcard_index} = {};
    $property->{property_aliases} = {};
}
for my $line (split /\n/, $metadata_sources[0]{text}) {
    next if $line =~ /^\s*#/;
    $line =~ s/#.*$//;
    my @fields = map { trim($_) } split /;/, $line;
    next unless @fields >= 3 && exists $by_key{$fields[0]};
    my $property = $by_key{$fields[0]};
    my $index = scalar @{$property->{short_values}};
    push @{$property->{short_values}}, $fields[1];
    push @{$property->{long_values}}, $fields[2];
    for my $alias (@fields[1 .. $#fields]) {
        next unless length $alias;
        my $loose = loose_name($alias);
        die "$property->{key} alias collision for '$alias'\n"
            if exists $property->{alias_index}{$loose}
                && $property->{alias_index}{$loose} != $index;
        $property->{alias_index}{$loose} = $index;
        die "$property->{key} wildcard alias collision for '$alias'\n"
            if exists $property->{wildcard_index}{$alias}
                && $property->{wildcard_index}{$alias} != $index;
        $property->{wildcard_index}{$alias} = $index;
    }
}

for my $line (split /\n/, $metadata_sources[1]{text}) {
    next if $line =~ /^\s*#/;
    $line =~ s/#.*$//;
    my @fields = map { trim($_) } split /;/, $line;
    next unless @fields >= 2;
    for my $property (@properties) {
        next unless grep { $_ eq $property->{key} || $_ eq $property->{long} } @fields;
        $property->{property_aliases}{loose_name($_)} = 1 for grep { length } @fields;
    }
}

my %expected_values = (GCB => 18, SB => 15, WB => 23, lb => 49);
for my $property (@properties) {
    my $count = scalar @{$property->{short_values}};
    die "Expected $expected_values{$property->{key}} $property->{key} values, found $count\n"
        unless $count == $expected_values{$property->{key}};
    die "Pinned aliases do not define $property->{key} and $property->{long}\n"
        unless $property->{property_aliases}{loose_name($property->{key})}
            && $property->{property_aliases}{loose_name($property->{long})};

    my (@missing, @explicit);
    for my $line (split /\n/, $property->{text}) {
        if ($line =~ /^#\s*\@missing:\s*([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z0-9_]+)/) {
            my ($range, $value) = ($1, $2);
            my ($start, $end) = parse_range($range);
            my $index = $property->{alias_index}{loose_name($value)};
            die "Unknown $property->{key} missing value '$value'\n" unless defined $index;
            push @missing, [$start, $end, $index];
            next;
        }
        next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z0-9_]+)/;
        my ($range, $value) = ($1, $2);
        my ($start, $end) = parse_range($range);
        my $index = $property->{alias_index}{loose_name($value)};
        die "Unknown $property->{key} explicit value '$value'\n" unless defined $index;
        push @explicit, [$start, $end, $index];
    }
    die "$property->{key} data has no ranges or defaults\n" unless @explicit && @missing;
    $property->{missing_count} = scalar @missing;
    $property->{explicit_count} = scalar @explicit;

    my $default_index = $property->{alias_index}{loose_name($property->{default})};
    die "Missing default $property->{default} for $property->{key}\n"
        unless defined $default_index;
    my @code_value = ($default_index) x 0x110000;
    for my $range (@missing, @explicit) {
        my ($code, $end, $index) = @$range;
        $code_value[$code++] = $index while $code <= $end;
    }
    my @ranges;
    my ($range_start, $range_value) = (0, $code_value[0]);
    for my $code (1 .. 0x10ffff) {
        next if $code_value[$code] == $range_value;
        push @ranges, [$range_start, $code - 1, $range_value];
        ($range_start, $range_value) = ($code, $code_value[$code]);
    }
    push @ranges, [$range_start, 0x10ffff, $range_value];
    $property->{ranges} = \@ranges;
}

print <<'HEADER';
/*
 * Generated from Perl 5.44's pinned Unicode Character Database. Do not edit manually.
 *
HEADER
for my $source (@properties, @metadata_sources) {
    print " * Source: $source->{source_name}\n";
    for my $line (split /\n/, $source->{text}) {
        next unless $line =~ /^# (?:©|Unicode and|the U\.S\.|For terms of use and license)/;
        $line =~ s/^# / * /;
        print "$line\n";
    }
    print " *\n";
}
print <<'HEADER_END';
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;

final class PerlUnicodeBreakPropertyData {
HEADER_END

print "    static final String UNICODE_VERSION = \"$unicode_version\";\n";
for my $property (@properties) {
    my $constant = uc $property->{key};
    warn "Generating $constant with " . scalar(@{$property->{long_values}}) . " values\n"
        if $ENV{PERLONJAVA_GENERATOR_VERBOSE};
    print "    static final String ${constant}_SHA256 = \"$property->{hash}\";\n";
    print "    static final int ${constant}_MISSING_COUNT = $property->{missing_count};\n";
    print "    static final int ${constant}_EXPLICIT_RANGE_COUNT = $property->{explicit_count};\n";
}
print "    static final String PROP_VALUE_ALIASES_SHA256 = \"$metadata_sources[0]{hash}\";\n";
print "    static final String PROPERTY_ALIASES_SHA256 = \"$metadata_sources[1]{hash}\";\n\n";

sub java_string_array {
    my ($name, $values) = @_;
    print "    private static final String[] $name = {\n        ";
    print join(', ', map { qq{"$_"} } @$values);
    print "\n    };\n";
}

for my $property (@properties) {
    my $constant = uc $property->{key};
    java_string_array("${constant}_SHORT_VALUES", $property->{short_values});
    java_string_array("${constant}_LONG_VALUES", $property->{long_values});
    my @aliases = sort keys %{$property->{alias_index}};
    java_string_array("${constant}_VALUE_ALIASES", \@aliases);
    print "    private static final short[] ${constant}_VALUE_ALIAS_INDEX = {\n        ";
    print join(', ', map { $property->{alias_index}{$_} } @aliases);
    print "\n    };\n";
    my @wildcards = sort keys %{$property->{wildcard_index}};
    java_string_array("${constant}_WILDCARD_VALUES", \@wildcards);
    my @property_aliases = sort keys %{$property->{property_aliases}};
    java_string_array("${constant}_PROPERTY_ALIASES", \@property_aliases);
    print "    private static final int[] ${constant}_RANGES = build${constant}Ranges();\n";
    print "    private static int[] build${constant}Ranges() {\n";
    my $ranges = $property->{ranges};
    my $chunk_size = 300;
    my $chunk_count = int((@$ranges + $chunk_size - 1) / $chunk_size);
    print "        int[] ranges = new int[" . (3 * @$ranges) . "];\n";
    for my $chunk (0 .. $chunk_count - 1) {
        print "        fill${constant}Ranges$chunk(ranges);\n";
    }
    print "        return ranges;\n";
    print "    }\n\n";
    for my $chunk (0 .. $chunk_count - 1) {
        my $start = $chunk * $chunk_size;
        my $end = $start + $chunk_size - 1 < $#$ranges
            ? $start + $chunk_size - 1 : $#$ranges;
        print "    private static void fill${constant}Ranges$chunk(int[] ranges) {\n";
        print "        int[] chunk = {\n";
        for (my $i = $start; $i <= $end; $i += 4) {
            my $line_end = $i + 3 < $end ? $i + 3 : $end;
            print "            ", join(', ', map {
                sprintf '0x%X, 0x%X, %d', @{$ranges->[$_]}[0, 1, 2]
            } $i .. $line_end), ",\n";
        }
        print "        };\n";
        print "        System.arraycopy(chunk, 0, ranges, " . (3 * $start)
            . ", chunk.length);\n";
        print "    }\n\n";
    }
}

print <<'FOOTER';
    private static final String[][] SHORT_VALUES = {
            GCB_SHORT_VALUES, SB_SHORT_VALUES, WB_SHORT_VALUES, LB_SHORT_VALUES
    };
    private static final String[][] LONG_VALUES = {
            GCB_LONG_VALUES, SB_LONG_VALUES, WB_LONG_VALUES, LB_LONG_VALUES
    };
    private static final String[][] VALUE_ALIASES = {
            GCB_VALUE_ALIASES, SB_VALUE_ALIASES, WB_VALUE_ALIASES, LB_VALUE_ALIASES
    };
    private static final short[][] VALUE_ALIAS_INDEX = {
            GCB_VALUE_ALIAS_INDEX, SB_VALUE_ALIAS_INDEX, WB_VALUE_ALIAS_INDEX,
            LB_VALUE_ALIAS_INDEX
    };
    private static final String[][] WILDCARD_VALUES = {
            GCB_WILDCARD_VALUES, SB_WILDCARD_VALUES, WB_WILDCARD_VALUES,
            LB_WILDCARD_VALUES
    };
    private static final String[][] PROPERTY_ALIASES = {
            GCB_PROPERTY_ALIASES, SB_PROPERTY_ALIASES, WB_PROPERTY_ALIASES,
            LB_PROPERTY_ALIASES
    };
    private static final int[][] RANGES = {GCB_RANGES, SB_RANGES, WB_RANGES, LB_RANGES};
    private static final UnicodeSet[][] VALUE_SETS = buildValueSets();

    static boolean isPropertyAlias(String alias) {
        return propertyIndex(alias) >= 0;
    }

    static UnicodeSet valueSet(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        if (property < 0) return null;
        int value = valueIndex(property, valueAlias);
        return value < 0 ? null : VALUE_SETS[property][value];
    }

    static String shortValue(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        if (property < 0) return null;
        int value = valueIndex(property, valueAlias);
        return value < 0 ? null : SHORT_VALUES[property][value];
    }

    static String canonicalValue(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        if (property < 0) return null;
        int value = valueIndex(property, valueAlias);
        return value < 0 ? null : LONG_VALUES[property][value];
    }

    static String[] canonicalValues(String propertyAlias) {
        int property = propertyIndex(propertyAlias);
        return property < 0 ? null : LONG_VALUES[property].clone();
    }

    static String[] wildcardValues(String propertyAlias) {
        int property = propertyIndex(propertyAlias);
        return property < 0 ? null : WILDCARD_VALUES[property].clone();
    }

    private static int propertyIndex(String alias) {
        String loose = looseName(alias);
        if (loose == null) return -1;
        for (int property = 0; property < PROPERTY_ALIASES.length; property++) {
            for (String candidate : PROPERTY_ALIASES[property]) {
                if (candidate.equals(loose)) return property;
            }
        }
        return -1;
    }

    private static int valueIndex(int property, String alias) {
        String loose = looseName(alias);
        if (loose == null) return -1;
        for (int i = 0; i < VALUE_ALIASES[property].length; i++) {
            if (VALUE_ALIASES[property][i].equals(loose)) {
                return VALUE_ALIAS_INDEX[property][i];
            }
        }
        return -1;
    }

    private static String looseName(String name) {
        if (name == null) return null;
        StringBuilder loose = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (character == '_' || character == '-' || Character.isWhitespace(character)) continue;
            loose.append(Character.toLowerCase(character));
        }
        return loose.toString();
    }

    private static UnicodeSet[][] buildValueSets() {
        UnicodeSet[][] sets = new UnicodeSet[LONG_VALUES.length][];
        for (int property = 0; property < sets.length; property++) {
            sets[property] = new UnicodeSet[LONG_VALUES[property].length];
            for (int value = 0; value < sets[property].length; value++) {
                sets[property][value] = new UnicodeSet();
            }
            for (int i = 0; i < RANGES[property].length; i += 3) {
                sets[property][RANGES[property][i + 2]].add(
                        RANGES[property][i], RANGES[property][i + 1]);
            }
            for (UnicodeSet set : sets[property]) set.freeze();
        }
        return sets;
    }

    private PerlUnicodeBreakPropertyData() {
    }
}
FOOTER
