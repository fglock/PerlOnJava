#!/usr/bin/env perl
use strict;
use warnings;
use File::Spec;
use FindBin;
use lib File::Spec->catdir($FindBin::Bin, 'lib');
use PerlOnJava::UnicodeGenerator qw(
    emit_java_range_triples emit_unicode_source_notices loose_name parse_range
    read_pinned_source read_unicode_version repo_root select_unicode_root trim
);

my $expected_version = '17.0.0';
my $root = repo_root($FindBin::Bin);
my $unicore = select_unicode_root(
    repo_root => $root,
    version => $expected_version,
    required => [qw(version Scripts.txt ScriptExtensions.txt PropValueAliases.txt PropertyAliases.txt)],
);
my @sources = (
    {
        name => 'Scripts-17.0.0.txt',
        path => File::Spec->catfile($unicore, 'Scripts.txt'),
        hash => '9f5e50d3abaee7d6ce09480f325c706f485ae3240912527e651954d2d6b035bf',
        version => qr/^# Scripts-\Q$expected_version\E\.txt$/m,
    },
    {
        name => 'ScriptExtensions-17.0.0.txt',
        path => File::Spec->catfile($unicore, 'ScriptExtensions.txt'),
        hash => 'ec2107e58825a1586acee8e0911ce18260394ac8b87e535ca325f1ccbeb06bc6',
        version => qr/^# ScriptExtensions-\Q$expected_version\E\.txt$/m,
    },
    {
        name => 'PropertyValueAliases-17.0.0.txt',
        path => File::Spec->catfile($unicore, 'PropValueAliases.txt'),
        hash => '670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01',
        version => qr/^# PropertyValueAliases-\Q$expected_version\E\.txt$/m,
    },
    {
        name => 'PropertyAliases-17.0.0.txt',
        path => File::Spec->catfile($unicore, 'PropertyAliases.txt'),
        hash => '4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb',
        version => qr/^# PropertyAliases-\Q$expected_version\E\.txt$/m,
    },
);

sub read_source {
    my ($source) = @_;
    $source->{text} = read_pinned_source(
        path => $source->{path}, sha256 => $source->{hash},
        version_pattern => $source->{version}, unicode_version => $expected_version);
}

read_source($_) for @sources;

my $unicode_version = read_unicode_version(
    path => File::Spec->catfile($unicore, 'version'), expected => $expected_version);

my (@short_values, @long_values, %alias_index, %wildcard_value_index);
for my $line (split /\n/, $sources[2]{text}) {
    next if $line =~ /^\s*#/;
    $line =~ s/#.*$//;
    my @fields = map { trim($_) } split /;/, $line;
    next unless @fields >= 3 && $fields[0] eq 'sc';
    my $index = scalar @short_values;
    push @short_values, $fields[1];
    push @long_values, $fields[2];
    for my $alias (@fields[1 .. $#fields]) {
        next unless length $alias;
        my $loose = loose_name($alias);
        die "Script alias collision for '$alias'\n"
            if exists $alias_index{$loose} && $alias_index{$loose} != $index;
        $alias_index{$loose} = $index;
        die "Script wildcard alias collision for '$alias'\n"
            if exists $wildcard_value_index{$alias}
                && $wildcard_value_index{$alias} != $index;
        $wildcard_value_index{$alias} = $index;
    }
}
die "Expected 176 Script values, found " . scalar(@short_values) . "\n"
    unless @short_values == 176;

my (%script_property_aliases, %script_extensions_property_aliases);
for my $line (split /\n/, $sources[3]{text}) {
    next if $line =~ /^\s*#/;
    $line =~ s/#.*$//;
    my @fields = map { trim($_) } split /;/, $line;
    next unless @fields >= 2;
    if ($fields[0] eq 'sc' || $fields[1] eq 'Script') {
        $script_property_aliases{loose_name($_)} = 1 for grep { length } @fields;
    }
    if ($fields[0] eq 'scx' || $fields[1] eq 'Script_Extensions') {
        $script_extensions_property_aliases{loose_name($_)} = 1
            for grep { length } @fields;
    }
}
die "Pinned property aliases do not define sc and Script\n"
    unless $script_property_aliases{sc} && $script_property_aliases{script};
die "Pinned property aliases do not define scx and Script_Extensions\n"
    unless $script_extensions_property_aliases{scx}
        && $script_extensions_property_aliases{scriptextensions};

my (@script_missing, @script_explicit);
for my $line (split /\n/, $sources[0]{text}) {
    if ($line =~ /^#\s*\@missing:\s*([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z_]+)/) {
        my ($range, $value) = ($1, $2);
        my ($start, $end) = parse_range($range);
        my $index = $alias_index{loose_name($value)};
        die "Unknown \@missing Script value '$value'\n" unless defined $index;
        push @script_missing, [$start, $end, $index];
        next;
    }
    next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z_]+)/;
    my ($range, $value) = ($1, $2);
    my ($start, $end) = parse_range($range);
    my $index = $alias_index{loose_name($value)};
    die "Unknown explicit Script value '$value'\n" unless defined $index;
    push @script_explicit, [$start, $end, $index];
}
die "Script data has no ranges or defaults\n"
    unless @script_explicit && @script_missing;

my $unknown_index = $alias_index{loose_name('Unknown')};
my @script_code_value = ($unknown_index) x 0x110000;
for my $range (@script_missing) {
    my ($code, $end, $index) = @$range;
    $script_code_value[$code++] = $index while $code <= $end;
}
for my $range (@script_explicit) {
    my ($code, $end, $index) = @$range;
    $script_code_value[$code++] = $index while $code <= $end;
}

my @script_ranges;
my ($range_start, $range_value) = (0, $script_code_value[0]);
for my $code (1 .. 0x10ffff) {
    next if $script_code_value[$code] == $range_value;
    push @script_ranges, [$range_start, $code - 1, $range_value];
    ($range_start, $range_value) = ($code, $script_code_value[$code]);
}
push @script_ranges, [$range_start, 0x10ffff, $range_value];

my (@script_extensions_ranges, @script_extensions_values);
my @script_extensions_explicit = (0) x 0x110000;
my $saw_script_fallback = 0;
for my $line (split /\n/, $sources[1]{text}) {
    if ($line =~ /^#\s*\@missing:\s*0000\.\.10FFFF\s*;\s*<script>\s*$/) {
        $saw_script_fallback++;
        next;
    }
    next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([^#]+)/;
    my ($range, $values) = ($1, trim($2));
    my ($start, $end) = parse_range($range);
    my (@indexes, %seen_index);
    for my $value (split /\s+/, $values) {
        my $index = $alias_index{loose_name($value)};
        die "Unknown Script_Extensions value '$value'\n" unless defined $index;
        push @indexes, $index unless $seen_index{$index}++;
    }
    die "Empty Script_Extensions value set for '$range'\n" unless @indexes;
    for my $code ($start .. $end) {
        die sprintf "Overlapping Script_Extensions range at U+%04X\n", $code
            if $script_extensions_explicit[$code]++;
    }
    my $offset = scalar @script_extensions_values;
    push @script_extensions_values, @indexes;
    push @script_extensions_ranges, [$start, $end, $offset, scalar @indexes];
}
die "Expected exactly one Script_Extensions <script> fallback, found $saw_script_fallback\n"
    unless $saw_script_fallback == 1;
die "Script_Extensions data has no explicit overrides\n"
    unless @script_extensions_ranges;

print <<'HEADER';
/*
 * Generated from Perl 5.44's pinned Unicode Character Database. Do not edit manually.
 *
HEADER
emit_unicode_source_notices(\@sources);
print <<'HEADER_END';
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;

final class PerlUnicodeScriptData {
HEADER_END

print "    static final String UNICODE_VERSION = \"$unicode_version\";\n";
print "    static final String SCRIPTS_SHA256 = \"$sources[0]{hash}\";\n";
print "    static final String SCRIPT_EXTENSIONS_SHA256 = \"$sources[1]{hash}\";\n";
print "    static final String PROP_VALUE_ALIASES_SHA256 = \"$sources[2]{hash}\";\n";
print "    static final String PROPERTY_ALIASES_SHA256 = \"$sources[3]{hash}\";\n\n";

print "    private static final String[] SHORT_VALUES = {\n        ";
print join(', ', map { qq{\"$_\"} } @short_values);
print "\n    };\n";
print "    private static final String[] LONG_VALUES = {\n        ";
print join(', ', map { qq{\"$_\"} } @long_values);
print "\n    };\n";

my @aliases = sort keys %alias_index;
print "    private static final String[] VALUE_ALIASES = {\n        ";
print join(', ', map { qq{\"$_\"} } @aliases);
print "\n    };\n";
print "    private static final short[] VALUE_ALIAS_INDEX = {\n        ";
print join(', ', map { $alias_index{$_} } @aliases);
print "\n    };\n";

my @wildcard_values = sort keys %wildcard_value_index;
print "    private static final String[] WILDCARD_VALUES = {\n        ";
print join(', ', map { qq{\"$_\"} } @wildcard_values);
print "\n    };\n";

my @script_property_aliases = sort keys %script_property_aliases;
print "    private static final String[] SCRIPT_PROPERTY_ALIASES = {\n        ";
print join(', ', map { qq{\"$_\"} } @script_property_aliases);
print "\n    };\n";
my @script_extensions_property_aliases = sort keys %script_extensions_property_aliases;
print "    private static final String[] SCRIPT_EXTENSIONS_PROPERTY_ALIASES = {\n        ";
print join(', ', map { qq{\"$_\"} } @script_extensions_property_aliases);
print "\n    };\n\n";

print "    private static final int[] SCRIPT_RANGES = {\n";
emit_java_range_triples(\@script_ranges);
print "    };\n\n";

print "    private static final int[] SCRIPT_EXTENSIONS_RANGES = {\n";
for (my $i = 0; $i < @script_extensions_ranges; $i += 4) {
    my $end = $i + 3 < $#script_extensions_ranges
        ? $i + 3 : $#script_extensions_ranges;
    print "        ", join(', ', map {
        sprintf '0x%X, 0x%X, %d, %d', @{$script_extensions_ranges[$_]}
    } $i .. $end), ",\n";
}
print "    };\n";
print "    private static final short[] SCRIPT_EXTENSIONS_VALUES = {\n        ";
print join(', ', @script_extensions_values);
print "\n    };\n\n";

print <<'FOOTER';
    private static final UnicodeSet[] SCRIPT_SETS = buildScriptSets();
    private static final UnicodeSet[] SCRIPT_EXTENSIONS_SETS =
            buildScriptExtensionsSets();

    static boolean isScriptPropertyAlias(String alias) {
        return containsLooseAlias(SCRIPT_PROPERTY_ALIASES, alias);
    }

    static boolean isScriptExtensionsPropertyAlias(String alias) {
        return containsLooseAlias(SCRIPT_EXTENSIONS_PROPERTY_ALIASES, alias);
    }

    static UnicodeSet scriptSet(String alias) {
        int index = valueIndex(alias);
        return index < 0 ? null : SCRIPT_SETS[index];
    }

    static UnicodeSet scriptExtensionsSet(String alias) {
        int index = valueIndex(alias);
        return index < 0 ? null : SCRIPT_EXTENSIONS_SETS[index];
    }

    static String shortValue(String alias) {
        int index = valueIndex(alias);
        return index < 0 ? null : SHORT_VALUES[index];
    }

    static String canonicalValue(String alias) {
        int index = valueIndex(alias);
        return index < 0 ? null : LONG_VALUES[index];
    }

    static String[] canonicalValues() {
        return LONG_VALUES.clone();
    }

    static String[] wildcardValues() {
        return WILDCARD_VALUES.clone();
    }

    private static boolean containsLooseAlias(String[] aliases, String alias) {
        String loose = looseName(alias);
        if (loose == null) return false;
        for (String candidate : aliases) {
            if (candidate.equals(loose)) return true;
        }
        return false;
    }

    private static int valueIndex(String alias) {
        String loose = looseName(alias);
        if (loose == null) return -1;
        for (int i = 0; i < VALUE_ALIASES.length; i++) {
            if (VALUE_ALIASES[i].equals(loose)) return VALUE_ALIAS_INDEX[i];
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

    private static UnicodeSet[] buildScriptSets() {
        UnicodeSet[] sets = new UnicodeSet[LONG_VALUES.length];
        for (int i = 0; i < sets.length; i++) sets[i] = new UnicodeSet();
        for (int i = 0; i < SCRIPT_RANGES.length; i += 3) {
            sets[SCRIPT_RANGES[i + 2]].add(SCRIPT_RANGES[i], SCRIPT_RANGES[i + 1]);
        }
        for (UnicodeSet set : sets) set.freeze();
        return sets;
    }

    private static UnicodeSet[] buildScriptExtensionsSets() {
        UnicodeSet[] sets = new UnicodeSet[SCRIPT_SETS.length];
        for (int i = 0; i < sets.length; i++) sets[i] = new UnicodeSet(SCRIPT_SETS[i]);
        for (int i = 0; i < SCRIPT_EXTENSIONS_RANGES.length; i += 4) {
            int start = SCRIPT_EXTENSIONS_RANGES[i];
            int end = SCRIPT_EXTENSIONS_RANGES[i + 1];
            int offset = SCRIPT_EXTENSIONS_RANGES[i + 2];
            int length = SCRIPT_EXTENSIONS_RANGES[i + 3];
            for (UnicodeSet set : sets) set.remove(start, end);
            for (int j = offset; j < offset + length; j++) {
                sets[SCRIPT_EXTENSIONS_VALUES[j]].add(start, end);
            }
        }
        for (UnicodeSet set : sets) set.freeze();
        return sets;
    }

    private PerlUnicodeScriptData() {
    }
}
FOOTER
