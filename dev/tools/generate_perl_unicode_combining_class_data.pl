#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);

my $unicode_root = 'perl5/lib/unicore';
my $expected_unicode_version = '17.0.0';
my %expected_hash = (
    'extracted/DCombiningClass.txt' =>
        '191463abfbd202703c6fd6776a92a23ac44ec65e0476a7f95aa91ca492cef29b',
    'PropValueAliases.txt' =>
        '670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01',
);

open my $version_fh, '<', "$unicode_root/version"
    or die "Can't read Unicode version: $!\n";
chomp(my $unicode_version = <$version_fh>);
close $version_fh;
die "Expected Unicode $expected_unicode_version, found $unicode_version\n"
    unless $unicode_version eq $expected_unicode_version;

sub read_pinned_file {
    my ($relative) = @_;
    my $path = "$unicode_root/$relative";
    open my $fh, '<:raw', $path or die "Can't read $path: $!\n";
    local $/;
    my $text = <$fh>;
    close $fh;
    my $actual = sha256_hex($text);
    die "$path SHA-256 mismatch: expected $expected_hash{$relative}, found $actual\n"
        unless $actual eq $expected_hash{$relative};
    return $text;
}

sub normalized_alias {
    my ($alias) = @_;
    $alias = lc $alias;
    $alias =~ s/[\s_+\-]//g;
    $alias =~ s/^0+(?=\d)// if $alias =~ /^\d+$/;
    return $alias;
}

my $class_text = read_pinned_file('extracted/DCombiningClass.txt');
die "Combining class data is not Unicode $expected_unicode_version\n"
    unless $class_text =~ /^# DerivedCombiningClass-\Q$expected_unicode_version\E\.txt/m;

my (@values, %value_index, @ranges);
for my $line (split /\n/, $class_text) {
    next unless $line =~ /^([0-9A-F]+)(?:\.\.([0-9A-F]+))?\s*;\s*([0-9]+)/;
    my ($start, $end, $value) =
        (hex($1), defined($2) ? hex($2) : hex($1), int($3));
    if (!exists $value_index{$value}) {
        $value_index{$value} = scalar @values;
        push @values, $value;
    }
    my $index = $value_index{$value};
    if (@ranges && $ranges[-1][2] == $index && $ranges[-1][1] + 1 == $start) {
        $ranges[-1][1] = $end;
    } else {
        push @ranges, [$start, $end, $index];
    }
}
die "No Canonical_Combining_Class ranges found\n" unless @ranges;
die "Unexpected Canonical_Combining_Class default\n"
    unless $class_text =~ /^# \@missing:\s*0000\.\.10FFFF;\s*Not_Reordered\s*$/m;

my $alias_text = read_pinned_file('PropValueAliases.txt');
die "Property value aliases are not Unicode $expected_unicode_version\n"
    unless $alias_text =~ /^# PropertyValueAliases-\Q$expected_unicode_version\E\.txt/m;
my %aliases;
for my $line (split /\n/, $alias_text) {
    $line =~ s/\s*#.*$//;
    next unless $line =~ /\S/;
    my @fields = map {
        my $field = $_;
        $field =~ s/^\s+|\s+$//g;
        $field;
    } split /;/, $line;
    next unless @fields >= 4 && lc($fields[0]) eq 'ccc';
    my $canonical = int($fields[1]);
    if (!exists $value_index{$canonical}) {
        $value_index{$canonical} = scalar @values;
        push @values, $canonical;
    }
    for my $alias (@fields[1 .. $#fields]) {
        next unless length $alias;
        my $normalized = normalized_alias($alias);
        if (exists $aliases{$normalized} && $aliases{$normalized} != $canonical) {
            die "Conflicting combining-class alias $alias\n";
        }
        $aliases{$normalized} = $canonical;
    }
}
for my $value (@values) {
    $aliases{normalized_alias($value)} //= $value;
}

print <<'HEADER';
/*
 * Generated from Perl 5.44's Unicode Character Database. Do not edit manually.
 *
 * Unicode data source copyright:
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in
 * the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;

import java.util.HashMap;
import java.util.Map;

final class PerlUnicodeCombiningClassData {
HEADER

print "    static final String UNICODE_VERSION = \"$unicode_version\";\n";
print "    private static final int DEFAULT_VALUE_INDEX = $value_index{0};\n";
print "    private static final int[] VALUES = {\n        ";
print join(', ', @values);
print "\n    };\n\n";

my $range_chunk_size = 400;
my $range_chunk_count = int((@ranges + $range_chunk_size - 1) / $range_chunk_size);
for my $chunk (0 .. $range_chunk_count - 1) {
    my $first = $chunk * $range_chunk_size;
    my $last = $first + $range_chunk_size - 1;
    $last = $#ranges if $last > $#ranges;
    print "    private static int[] rangeChunk$chunk() {\n";
    print "        return new int[] {\n";
    for (my $i = $first; $i <= $last; $i += 4) {
        my $end = $i + 3 < $last ? $i + 3 : $last;
        print "            ", join(', ', map {
            sprintf '0x%X, 0x%X, %d', @{$ranges[$_]}[0, 1, 2]
        } $i .. $end), ",\n";
    }
    print "        };\n";
    print "    }\n\n";
}
print "    private static final int[][] RANGE_CHUNKS = {\n        ";
print join(', ', map { "rangeChunk$_()" } 0 .. $range_chunk_count - 1);
print "\n    };\n\n";

print "    private static final String[] ALIASES = {\n";
my @alias_names = sort keys %aliases;
for (my $i = 0; $i < @alias_names; $i += 4) {
    my $end = $i + 3 < $#alias_names ? $i + 3 : $#alias_names;
    print "        ", join(', ', map {
        my $name = $alias_names[$_];
        qq{"$name", "} . $aliases{$name} . qq{"}
    } $i .. $end), ",\n";
}
print <<'FOOTER';
    };

    private static final UnicodeSet[] SETS = buildSets();
    private static final Map<String, Integer> ALIAS_INDEX = buildAliasIndex();

    static UnicodeSet resolve(String value) {
        Integer index = ALIAS_INDEX.get(normalizeValue(value));
        return index == null ? null : SETS[index];
    }

    private static UnicodeSet[] buildSets() {
        UnicodeSet[] sets = new UnicodeSet[VALUES.length];
        for (int i = 0; i < sets.length; i++) sets[i] = new UnicodeSet();
        UnicodeSet covered = new UnicodeSet();
        for (int[] ranges : RANGE_CHUNKS) {
            for (int i = 0; i < ranges.length; i += 3) {
                sets[ranges[i + 2]].add(ranges[i], ranges[i + 1]);
                covered.add(ranges[i], ranges[i + 1]);
            }
        }
        sets[DEFAULT_VALUE_INDEX].addAll(
                new UnicodeSet(0, 0x10FFFF).removeAll(covered));
        for (UnicodeSet set : sets) set.freeze();
        return sets;
    }

    private static Map<String, Integer> buildAliasIndex() {
        Map<Integer, Integer> valueIndexes = new HashMap<>();
        for (int i = 0; i < VALUES.length; i++) valueIndexes.put(VALUES[i], i);
        Map<String, Integer> indexes = new HashMap<>();
        for (int i = 0; i < ALIASES.length; i += 2) {
            indexes.put(ALIASES[i], valueIndexes.get(Integer.parseInt(ALIASES[i + 1])));
        }
        return Map.copyOf(indexes);
    }

    private static String normalizeValue(String value) {
        String normalized = value.trim();
        if (normalized.startsWith(":\\A") && normalized.endsWith("\\z:")
                && normalized.length() > 6) {
            normalized = normalized.substring(3, normalized.length() - 3);
        }
        StringBuilder loose = new StringBuilder(normalized.length());
        boolean numeric = true;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isWhitespace(ch) || ch == '-' || ch == '_' || ch == '+') continue;
            if (!Character.isDigit(ch)) numeric = false;
            loose.append(Character.toLowerCase(ch));
        }
        if (!numeric) return loose.toString();
        int first = 0;
        while (first + 1 < loose.length() && loose.charAt(first) == '0') first++;
        return loose.substring(first);
    }

    private PerlUnicodeCombiningClassData() {
    }
}
FOOTER
