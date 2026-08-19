#!/usr/bin/env perl
use strict;
use warnings;
use File::Spec;
use FindBin;
use lib File::Spec->catdir($FindBin::Bin, 'lib');
use PerlOnJava::UnicodeGenerator qw(
    emit_java_range_triples loose_name read_pinned_source read_unicode_version repo_root select_unicode_root
);

my $expected_unicode_version = '17.0.0';
my %expected_hash = (
    'extracted/DGeneralCategory.txt' =>
        'd62e5bab70ca74f099343f71224fa051cb1fdd61a1ab45c0488c44cfc0b6102e',
    'PropValueAliases.txt' =>
        '670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01',
);
my $unicode_root = select_unicode_root(
    repo_root => repo_root($FindBin::Bin), version => $expected_unicode_version,
    required => [qw(version PropValueAliases.txt), File::Spec->catfile('extracted', 'DGeneralCategory.txt')]);

my $unicode_version = read_unicode_version(
    path => File::Spec->catfile($unicode_root, 'version'), expected => $expected_unicode_version,
    sha256 => '8c30575264b2772c7a69c5bb6069a28f0e0a7a0df735871bde2d99ee674316ac');

sub read_pinned_file {
    my ($relative) = @_;
    my $path = File::Spec->catfile($unicode_root, split m{/}, $relative);
    return read_pinned_source(path => $path, sha256 => $expected_hash{$relative});
}

my $category_text = read_pinned_file('extracted/DGeneralCategory.txt');
die "General_Category data is not Unicode $expected_unicode_version\n"
    unless $category_text =~ /^# DerivedGeneralCategory-\Q$expected_unicode_version\E\.txt/m;

my (@values, %value_index, @ranges);
for my $line (split /\n/, $category_text) {
    next unless $line =~ /^([0-9A-F]+)(?:\.\.([0-9A-F]+))?\s*;\s*([A-Za-z]+)/;
    my ($start, $end, $value) =
        (hex($1), defined($2) ? hex($2) : hex($1), $3);
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
die "No General_Category ranges found\n" unless @ranges;

# DerivedGeneralCategory contains the 30 atomic values. Perl also exposes the
# Unicode aggregate aliases from PropertyValueAliases.txt; materialize their
# unions from the same pinned atomic ranges so no host Unicode table is used.
my %composite = (
    C  => [qw(Cc Cf Cn Co Cs)],
    L  => [qw(Ll Lm Lo Lt Lu)],
    LC => [qw(Ll Lt Lu)],
    M  => [qw(Mc Me Mn)],
    N  => [qw(Nd Nl No)],
    P  => [qw(Pc Pd Pe Pf Pi Po Ps)],
    S  => [qw(Sc Sk Sm So)],
    Z  => [qw(Zl Zp Zs)],
);
my @atomic_ranges = @ranges;
for my $value (qw(C L LC M N P S Z)) {
    $value_index{$value} = scalar @values;
    push @values, $value;
    my %member = map {
        die "Composite $value references unknown category $_\n"
            unless exists $value_index{$_};
        $value_index{$_} => 1;
    } @{$composite{$value}};
    push @ranges, map {
        [$_->[0], $_->[1], $value_index{$value}]
    } grep { $member{$_->[2]} } @atomic_ranges;
}

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
    next unless @fields >= 3 && lc($fields[0]) eq 'gc';
    my $canonical = $fields[1];
    die "General_Category alias has unknown value $canonical\n"
        unless exists $value_index{$canonical};
    for my $alias (@fields[1 .. $#fields]) {
        next unless length $alias;
        my $loose = loose_name($alias);
        if (exists $aliases{$loose} && $aliases{$loose} ne $canonical) {
            die "Conflicting General_Category alias $alias\n";
        }
        $aliases{$loose} = $canonical;
    }
}
for my $value (@values) {
    $aliases{loose_name($value)} //= $value;
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

final class PerlUnicodeGeneralCategoryData {
HEADER

print "    static final String UNICODE_VERSION = \"$unicode_version\";\n";
print "    private static final String[] VALUES = {\n        ";
print join(', ', map { qq{"$_"} } @values);
print "\n    };\n\n";
my $range_chunk_size = 400;
my $range_chunk_count = int((@ranges + $range_chunk_size - 1) / $range_chunk_size);
for my $chunk (0 .. $range_chunk_count - 1) {
    my $first = $chunk * $range_chunk_size;
    my $last = $first + $range_chunk_size - 1;
    $last = $#ranges if $last > $#ranges;
    print "    private static int[] rangeChunk$chunk() {\n";
    print "        return new int[] {\n";
    emit_java_range_triples([@ranges[$first .. $last]], indent => '            ');
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
        for (int[] ranges : RANGE_CHUNKS) {
            for (int i = 0; i < ranges.length; i += 3) {
                sets[ranges[i + 2]].add(ranges[i], ranges[i + 1]);
            }
        }
        for (UnicodeSet set : sets) set.freeze();
        return sets;
    }

    private static Map<String, Integer> buildAliasIndex() {
        Map<String, Integer> valueIndexes = new HashMap<>();
        for (int i = 0; i < VALUES.length; i++) valueIndexes.put(VALUES[i], i);
        Map<String, Integer> indexes = new HashMap<>();
        for (int i = 0; i < ALIASES.length; i += 2) {
            indexes.put(ALIASES[i], valueIndexes.get(ALIASES[i + 1]));
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
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (!Character.isWhitespace(ch) && ch != '-' && ch != '_') {
                loose.append(Character.toLowerCase(ch));
            }
        }
        return loose.toString();
    }

    private PerlUnicodeGeneralCategoryData() {
    }
}
FOOTER
