#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin;
use lib File::Spec->catdir($FindBin::Bin, 'lib');
use PerlOnJava::UnicodeGenerator qw(
    emit_java_range_triples emit_unicode_source_notices loose_name parse_range
    read_raw repo_root select_unicode_root trim verify_unicode_notice
);

my $root = repo_root($FindBin::Bin);
my $unicore = select_unicode_root(
    repo_root => $root,
    version => 'current',
    required => [qw(version VerticalOrientation.txt PropValueAliases.txt PropertyAliases.txt)],
);
my $unicode_version = read_raw(File::Spec->catfile($unicore, 'version'));
$unicode_version =~ s/\s+\z//;
die "Malformed current Unicode version '$unicode_version'\n"
    unless $unicode_version =~ /\A\d+\.\d+\.\d+\z/;

my @sources = (
    {
        name => "VerticalOrientation-$unicode_version.txt",
        path => File::Spec->catfile($unicore, 'VerticalOrientation.txt'),
        version => qr/^# VerticalOrientation-\Q$unicode_version\E\.txt$/m,
    },
    {
        name => "PropertyValueAliases-$unicode_version.txt",
        path => File::Spec->catfile($unicore, 'PropValueAliases.txt'),
        version => qr/^# PropertyValueAliases-\Q$unicode_version\E\.txt$/m,
    },
    {
        name => "PropertyAliases-$unicode_version.txt",
        path => File::Spec->catfile($unicore, 'PropertyAliases.txt'),
        version => qr/^# PropertyAliases-\Q$unicode_version\E\.txt$/m,
    },
);
for my $source (@sources) {
    $source->{text} = read_raw($source->{path});
    $source->{hash} = sha256_hex($source->{text});
    die "$source->{path} is inconsistent with Unicode $unicode_version\n"
        unless $source->{text} =~ $source->{version};
    verify_unicode_notice($source->{path}, $source->{text});
}

my (@short_values, @long_values, %alias_index, %wildcard_alias_index);
for my $line (split /\n/, $sources[1]{text}) {
    next if $line =~ /^\s*#/;
    $line =~ s/#.*$//;
    my @fields = map { trim($_) } split /;/, $line;
    next unless @fields >= 3 && lc($fields[0]) eq 'vo';
    my $index = scalar @short_values;
    push @short_values, $fields[1];
    push @long_values, $fields[2];
    for my $alias (grep { length } @fields[1 .. $#fields]) {
        my $loose = loose_name($alias);
        die "Vertical_Orientation alias collision for '$alias'\n"
            if exists $alias_index{$loose} && $alias_index{$loose} != $index;
        die "Vertical_Orientation wildcard alias collision for '$alias'\n"
            if exists $wildcard_alias_index{$alias}
                && $wildcard_alias_index{$alias} != $index;
        $alias_index{$loose} = $index;
        $wildcard_alias_index{$alias} = $index;
    }
}
die "Expected four Vertical_Orientation values, found " . scalar(@short_values) . "\n"
    unless @short_values == 4;
for my $index (0 .. $#short_values) {
    die "Vertical_Orientation value '$long_values[$index]' has no wildcard alias\n"
        unless grep { $wildcard_alias_index{$_} == $index }
            keys %wildcard_alias_index;
}

my %property_aliases;
for my $line (split /\n/, $sources[2]{text}) {
    next if $line =~ /^\s*#/;
    $line =~ s/#.*$//;
    my @fields = map { trim($_) } split /;/, $line;
    next unless @fields >= 2
        && (lc($fields[0]) eq 'vo' || lc($fields[1]) eq 'vertical_orientation');
    $property_aliases{loose_name($_)} = 1 for grep { length } @fields;
}
die "Pinned property aliases do not define vo and Vertical_Orientation\n"
    unless $property_aliases{vo} && $property_aliases{verticalorientation};

my (@missing, @explicit);
for my $line (split /\n/, $sources[0]{text}) {
    if ($line =~ /^#\s*\@missing:\s*([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z_]+)/) {
        my ($start, $end) = parse_range($1);
        my $index = $alias_index{loose_name($2)};
        die "Unknown \@missing Vertical_Orientation value '$2'\n"
            unless defined $index;
        push @missing, [$start, $end, $index];
        next;
    }
    next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z_]+)/;
    my ($start, $end) = parse_range($1);
    my $index = $alias_index{loose_name($2)};
    die "Unknown Vertical_Orientation value '$2'\n" unless defined $index;
    push @explicit, [$start, $end, $index];
}
die "Vertical_Orientation data must have one full-range default and explicit values\n"
    unless @missing == 1 && $missing[0][0] == 0 && $missing[0][1] == 0x10ffff
        && @explicit;

my @code_value = ($missing[0][2]) x 0x110000;
for my $ranges (\@missing, \@explicit) {
    my $previous_end = -1;
    for my $range (@$ranges) {
        my ($start, $end, $index) = @$range;
        die "Vertical_Orientation range is invalid\n"
            if $start < 0 || $end > 0x10ffff || $start > $end;
        die "Vertical_Orientation ranges overlap or are out of order\n"
            if $start <= $previous_end;
        $previous_end = $end;
        $code_value[$_] = $index for $start .. $end;
    }
}

my @ranges;
my ($range_start, $range_value) = (0, $code_value[0]);
for my $code (1 .. 0x10ffff) {
    next if $code_value[$code] == $range_value;
    push @ranges, [$range_start, $code - 1, $range_value];
    ($range_start, $range_value) = ($code, $code_value[$code]);
}
push @ranges, [$range_start, 0x10ffff, $range_value];

print "/*\n";
print " * Generated from the current Perl checkout's Unicode Character Database. Do not edit manually.\n *\n";
emit_unicode_source_notices(\@sources);
print <<'HEADER_END';
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;

final class PerlUnicodeVerticalOrientationData {
HEADER_END

print "    static final String UNICODE_VERSION = \"$unicode_version\";\n";
print "    static final String VERTICAL_ORIENTATION_SHA256 = \"$sources[0]{hash}\";\n";
print "    static final String PROP_VALUE_ALIASES_SHA256 = \"$sources[1]{hash}\";\n";
print "    static final String PROPERTY_ALIASES_SHA256 = \"$sources[2]{hash}\";\n\n";

sub emit_string_array {
    my ($name, $values) = @_;
    print "    private static final String[] $name = {\n        ";
    print join(', ', map { qq{\"$_\"} } @$values);
    print "\n    };\n";
}

emit_string_array('SHORT_VALUES', \@short_values);
emit_string_array('LONG_VALUES', \@long_values);
my @aliases = sort keys %alias_index;
emit_string_array('VALUE_ALIASES', \@aliases);
print "    private static final byte[] VALUE_ALIAS_INDEX = {\n        ";
print join(', ', map { $alias_index{$_} } @aliases);
print "\n    };\n";
my @wildcard_aliases = sort keys %wildcard_alias_index;
emit_string_array('WILDCARD_ALIASES', \@wildcard_aliases);
print "    private static final byte[] WILDCARD_ALIAS_INDEX = {\n        ";
print join(', ', map { $wildcard_alias_index{$_} } @wildcard_aliases);
print "\n    };\n";
my @property_names = sort keys %property_aliases;
emit_string_array('PROPERTY_ALIASES', \@property_names);
print "    private static final int[] RANGES = {\n";
emit_java_range_triples(\@ranges);
print <<'FOOTER';
    };

    private static final UnicodeSet[] VALUE_SETS = buildValueSets();

    static boolean isPropertyAlias(String alias) {
        String loose = looseName(alias);
        if (loose == null) return false;
        for (String candidate : PROPERTY_ALIASES) {
            if (candidate.equals(loose)) return true;
        }
        return false;
    }

    static UnicodeSet valueSet(String alias) {
        int index = valueIndex(alias);
        return index < 0 ? null : VALUE_SETS[index];
    }

    static String canonicalValue(String alias) {
        int index = valueIndex(alias);
        return index < 0 ? null : LONG_VALUES[index];
    }

    static String[] canonicalValues() {
        return LONG_VALUES.clone();
    }

    static String[] wildcardAliases() {
        return WILDCARD_ALIASES.clone();
    }

    static String[] wildcardAliases(int index) {
        if (index < 0 || index >= LONG_VALUES.length) return new String[0];
        java.util.List<String> aliases = new java.util.ArrayList<>();
        for (int i = 0; i < WILDCARD_ALIAS_INDEX.length; i++) {
            if (WILDCARD_ALIAS_INDEX[i] == index) aliases.add(WILDCARD_ALIASES[i]);
        }
        return aliases.toArray(String[]::new);
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

    private static UnicodeSet[] buildValueSets() {
        UnicodeSet[] sets = new UnicodeSet[LONG_VALUES.length];
        for (int i = 0; i < sets.length; i++) sets[i] = new UnicodeSet();
        for (int i = 0; i < RANGES.length; i += 3) {
            sets[RANGES[i + 2]].add(RANGES[i], RANGES[i + 1]);
        }
        for (UnicodeSet set : sets) set.freeze();
        return sets;
    }

    private PerlUnicodeVerticalOrientationData() {
    }
}
FOOTER
