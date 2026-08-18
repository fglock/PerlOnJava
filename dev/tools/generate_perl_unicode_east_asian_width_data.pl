#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin;

my $expected_version = '17.0.0';
my $root = File::Spec->catdir($FindBin::Bin, '..', '..');
my $unicore = File::Spec->catdir($root, 'perl5', 'lib', 'unicore');
my @sources = (
    {
        name => 'DerivedEastAsianWidth-17.0.0.txt',
        path => File::Spec->catfile($unicore, 'extracted', 'DEastAsianWidth.txt'),
        hash => '0b5523a2217cb318d20b329a05d31eec5af5686ba09d263b85bb75a28989a3a8',
        version => qr/^# DerivedEastAsianWidth-\Q$expected_version\E\.txt$/m,
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

read_source($_) for @sources;

my $version_path = File::Spec->catfile($unicore, 'version');
open my $version_input, '<', $version_path or die "Cannot read $version_path: $!\n";
chomp(my $unicode_version = <$version_input>);
close $version_input or die "Cannot close $version_path: $!\n";
die "Expected Unicode $expected_version, found $unicode_version\n"
    unless $unicode_version eq $expected_version;

my (@short_values, @long_values, %alias_index);
for my $line (split /\n/, $sources[1]{text}) {
    next if $line =~ /^\s*#/;
    $line =~ s/#.*$//;
    my @fields = map { trim($_) } split /;/, $line;
    next unless @fields >= 3 && $fields[0] eq 'ea';
    my $index = scalar @short_values;
    push @short_values, $fields[1];
    push @long_values, $fields[2];
    for my $alias (@fields[1 .. $#fields]) {
        next unless length $alias;
        my $loose = loose_name($alias);
        die "East_Asian_Width alias collision for '$alias'\n"
            if exists $alias_index{$loose} && $alias_index{$loose} != $index;
        $alias_index{$loose} = $index;
    }
}
die "Expected 6 East_Asian_Width values, found " . scalar(@short_values) . "\n"
    unless @short_values == 6;

my %property_aliases;
for my $line (split /\n/, $sources[2]{text}) {
    next if $line =~ /^\s*#/;
    $line =~ s/#.*$//;
    my @fields = map { trim($_) } split /;/, $line;
    next unless @fields >= 2 && ($fields[0] eq 'ea' || $fields[1] eq 'East_Asian_Width');
    $property_aliases{loose_name($_)} = 1 for grep { length } @fields;
}
die "Pinned property aliases do not define ea and East_Asian_Width\n"
    unless $property_aliases{ea} && $property_aliases{eastasianwidth};

my (@missing, @explicit);
for my $line (split /\n/, $sources[0]{text}) {
    if ($line =~ /^#\s*\@missing:\s*([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z_]+)/) {
        my ($range, $value) = ($1, $2);
        my ($start, $end) = parse_range($range);
        my $index = $alias_index{loose_name($value)};
        die "Unknown \@missing East_Asian_Width value '$value'\n" unless defined $index;
        push @missing, [$start, $end, $index];
        next;
    }
    next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z_]+)/;
    my ($range, $value) = ($1, $2);
    my ($start, $end) = parse_range($range);
    my $index = $alias_index{loose_name($value)};
    die "Unknown explicit East_Asian_Width value '$value'\n" unless defined $index;
    push @explicit, [$start, $end, $index];
}
die "East_Asian_Width data has no ranges or defaults\n" unless @explicit && @missing;

my $default_index = $alias_index{loose_name('Neutral')};
my @code_value = ($default_index) x 0x110000;
for my $range (@missing) {
    my ($code, $end, $index) = @$range;
    $code_value[$code++] = $index while $code <= $end;
}
for my $range (@explicit) {
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

print <<'HEADER';
/*
 * Generated from Perl 5.44's pinned Unicode Character Database. Do not edit manually.
 *
HEADER
for my $source (@sources) {
    print " * Source: $source->{name}\n";
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

final class PerlUnicodeEastAsianWidthData {
HEADER_END

print "    static final String UNICODE_VERSION = \"$unicode_version\";\n";
print "    static final String DEAST_ASIAN_WIDTH_SHA256 = \"$sources[0]{hash}\";\n";
print "    static final String PROP_VALUE_ALIASES_SHA256 = \"$sources[1]{hash}\";\n";
print "    static final String PROPERTY_ALIASES_SHA256 = \"$sources[2]{hash}\";\n\n";

print "    private static final String[] SHORT_VALUES = {\n        ";
print join(', ', map { qq{"$_"} } @short_values);
print "\n    };\n";
print "    private static final String[] LONG_VALUES = {\n        ";
print join(', ', map { qq{"$_"} } @long_values);
print "\n    };\n";

my @aliases = sort keys %alias_index;
print "    private static final String[] VALUE_ALIASES = {\n        ";
print join(', ', map { qq{"$_"} } @aliases);
print "\n    };\n";
print "    private static final byte[] VALUE_ALIAS_INDEX = {\n        ";
print join(', ', map { $alias_index{$_} } @aliases);
print "\n    };\n";

my @property_aliases = sort keys %property_aliases;
print "    private static final String[] PROPERTY_ALIASES = {\n        ";
print join(', ', map { qq{"$_"} } @property_aliases);
print "\n    };\n\n";

print "    private static final int[] RANGES = {\n";
for (my $i = 0; $i < @ranges; $i += 4) {
    my $end = $i + 3 < $#ranges ? $i + 3 : $#ranges;
    print "        ", join(', ', map {
        sprintf '0x%X, 0x%X, %d', @{$ranges[$_]}[0, 1, 2]
    } $i .. $end), ",\n";
}
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

    private PerlUnicodeEastAsianWidthData() {
    }
}
FOOTER
