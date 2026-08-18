#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin;

binmode STDOUT, ':raw';

my $expected_version = '17.0.0';
my @required_sources = qw(version Blocks.txt PropertyAliases.txt PropValueAliases.txt);
my $local_unicore = File::Spec->catdir($FindBin::Bin, '..', '..', 'perl5', 'lib', 'unicore');
my $vendored_unicore = File::Spec->catdir($FindBin::Bin, '..', 'unicode', $expected_version);

sub missing_sources {
    my ($root) = @_;
    return grep { !-f File::Spec->catfile($root, $_) } @required_sources;
}

my @local_missing = missing_sources($local_unicore);
my @vendored_missing = missing_sources($vendored_unicore);
my $unicore = !@local_missing ? $local_unicore
    : !@vendored_missing ? $vendored_unicore
    : die "No complete Unicode $expected_version source tree: local missing "
        . join(', ', @local_missing) . '; vendored missing '
        . join(', ', @vendored_missing) . "\n";
my %sources = (
    Version => [File::Spec->catfile($unicore, 'version'),
        '8c30575264b2772c7a69c5bb6069a28f0e0a7a0df735871bde2d99ee674316ac'],
    Blocks => [File::Spec->catfile($unicore, 'Blocks.txt'),
        'c0edefaf1a19771e830a82735472716af6bf3c3975f6c2a23ffbe2580fbbcb15'],
    Property_Aliases => [File::Spec->catfile($unicore, 'PropertyAliases.txt'),
        '4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb'],
    Property_Value_Aliases => [File::Spec->catfile($unicore, 'PropValueAliases.txt'),
        '670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01'],
);

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
    $text = lc $text;
    $text =~ s/[\x09-\x0d _-]+//g;
    return $text;
}

sub range_from_text {
    my ($range) = @_;
    my ($first, $last) = split /\.\./, $range;
    return (hex($first), hex(defined $last ? $last : $first));
}

sub verify_unicode_notice {
    my ($path, $text) = @_;
    die "$path does not preserve the Unicode copyright notice\n"
        unless $text =~ /^# © 2025 Unicode®, Inc\.$/m;
    die "$path does not preserve the Unicode trademark notice\n"
        unless $text =~ /^# Unicode and the Unicode Logo are registered trademarks of Unicode, Inc\. in the U\.S\. and other countries\.$/m;
    die "$path does not preserve the Unicode terms notice\n"
        unless $text =~ m{^# For terms of use and license, see https://www\.unicode\.org/terms_of_use\.html$}m;
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
    next unless @fields >= 2 && $fields[0] eq 'blk';
    @property_aliases = grep { length } @fields;
}
die "Unexpected Block aliases in $property_path: @property_aliases\n"
    unless join("\0", @property_aliases) eq join("\0", 'blk', 'Block');

my ($value_path, $value_text) = source_text('Property_Value_Aliases');
die "$value_path is not pinned Unicode $expected_version data\n"
    unless $value_text =~ /^# PropertyValueAliases-\Q$expected_version\E\.txt$/m;
verify_unicode_notice($value_path, $value_text);
my (@value_rows, %row_for_long, %row_for_alias);
my $in_block_values = 0;
for my $line (split /\n/, $value_text) {
    if ($line =~ /^# Block \(blk\)$/) {
        $in_block_values = 1;
        next;
    }
    last if $in_block_values && $line =~ /^# /;
    next unless $in_block_values && $line =~ /^blk\s*;/;
    $line =~ s/#.*//;
    my @fields = map { trim($_) } split /;/, $line, -1;
    shift @fields;
    @fields = grep { length } @fields;
    die "Block value record lacks short/long aliases: '$line'\n" unless @fields >= 2;
    my $row = scalar @value_rows;
    push @value_rows, \@fields;
    my $long_key = loose($fields[1]);
    die "Duplicate Block long alias '$fields[1]'\n" if exists $row_for_long{$long_key};
    $row_for_long{$long_key} = $row;
    for my $alias (@fields) {
        my $key = loose($alias);
        die "Block alias '$alias' collides across values\n"
            if exists $row_for_alias{$key} && $row_for_alias{$key} != $row;
        $row_for_alias{$key} = $row;
    }
}
die "Expected 347 Block value records, found " . scalar(@value_rows) . "\n"
    unless @value_rows == 347;
my %exact_aliases;
my $alias_field_count = 0;
for my $row (@value_rows) {
    $alias_field_count += @$row;
    $exact_aliases{$_} = 1 for @$row;
}
die "Expected 700 Block alias fields, found $alias_field_count\n"
    unless $alias_field_count == 700;
die "Expected 496 exact Block aliases, found " . scalar(keys %exact_aliases) . "\n"
    unless keys(%exact_aliases) == 496;
die "Expected 495 loose Block aliases, found " . scalar(keys %row_for_alias) . "\n"
    unless keys(%row_for_alias) == 495;
my $no_block_row = $row_for_long{loose('No_Block')};
die "Missing No_Block aliases\n" unless defined $no_block_row;

my ($blocks_path, $blocks_text) = source_text('Blocks');
die "$blocks_path is not pinned Unicode $expected_version data\n"
    unless $blocks_text =~ /^# Blocks-\Q$expected_version\E\.txt$/m;
verify_unicode_notice($blocks_path, $blocks_text);
my (@missing, @explicit);
for my $line (split /\n/, $blocks_text) {
    if ($line =~ /^\#\s*\@missing:\s*([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;
            \s*([^#]+?)\s*$/x) {
        my ($first, $last) = range_from_text($1);
        push @missing, [$first, $last, trim($2)];
        next;
    }
    next if $line =~ /^\s*(?:#|$)/;
    die "Malformed Blocks record '$line'\n"
        unless $line =~ /^([0-9A-F]+\.\.[0-9A-F]+)\s*;\s*([^#]+?)\s*$/;
    my ($first, $last) = range_from_text($1);
    push @explicit, [$first, $last, trim($2)];
}
die "Expected one ordered Block \@missing rule\n"
    unless @missing == 1 && $missing[0][0] == 0 && $missing[0][1] == 0x10ffff
        && loose($missing[0][2]) eq loose('No_Block');
die "Expected 346 explicit Block ranges, found " . scalar(@explicit) . "\n"
    unless @explicit == 346;

my @value_names = ('No_Block');
my %value_id_for_row = ($no_block_row => 0);
my $explicit_count = 0;
for my $index (0 .. $#explicit) {
    my ($first, $last, $source_name) = @{$explicit[$index]};
    die sprintf("Invalid Block range U+%04X..U+%04X\n", $first, $last)
        if $first < 0 || $last > 0x10ffff || $first > $last;
    die sprintf("Block range is not aligned to hex columns: U+%04X..U+%04X\n", $first, $last)
        if ($first & 0xf) != 0 || ($last & 0xf) != 0xf;
    die sprintf("Overlapping or out-of-order Block range at U+%04X\n", $first)
        if $index > 0 && $first <= $explicit[$index - 1][1];
    my $row = $row_for_long{loose($source_name)};
    die "Block '$source_name' has no long value alias\n" unless defined $row;
    die "Block '$source_name' is represented by more than one range\n"
        if exists $value_id_for_row{$row};
    my $value_id = scalar @value_names;
    $value_id_for_row{$row} = $value_id;
    push @value_names, $value_rows[$row][1];
    $explicit[$index][2] = $value_id;
    $explicit_count += $last - $first + 1;
}
die "Expected 347 reachable Block values, found " . scalar(@value_names) . "\n"
    unless @value_names == 347 && keys(%value_id_for_row) == 347;
die "Expected 303,808 named-Block code points, found $explicit_count\n"
    unless $explicit_count == 303_808;

my @partition;
my $cursor = 0;
for my $range (@explicit) {
    push @partition, [$cursor, $range->[0] - 1, 0] if $cursor < $range->[0];
    push @partition, [@$range];
    $cursor = $range->[1] + 1;
}
push @partition, [$cursor, 0x10ffff, 0] if $cursor <= 0x10ffff;
die "Expected 397 complete Block intervals, found " . scalar(@partition) . "\n"
    unless @partition == 397;
my ($partition_count, $no_block_count, $no_block_ranges) = (0, 0, 0);
for my $index (0 .. $#partition) {
    my ($first, $last, $value_id) = @{$partition[$index]};
    die "Block partition is not contiguous\n"
        if ($index == 0 && $first != 0)
            || ($index > 0 && $first != $partition[$index - 1][1] + 1);
    die "Block partition value is out of bounds\n"
        if $value_id < 0 || $value_id >= @value_names;
    my $count = $last - $first + 1;
    $partition_count += $count;
    if ($value_id == 0) {
        $no_block_count += $count;
        $no_block_ranges++;
    }
}
die "Block partition does not end at U+10FFFF\n"
    unless $partition[-1][1] == 0x10ffff;
die "Block partition does not cover the Unicode scalar universe\n"
    unless $partition_count == 0x110000;
die "Expected 51 No_Block ranges and 810,304 code points\n"
    unless $no_block_ranges == 51 && $no_block_count == 810_304;

my %alias_value_id;
for my $key (keys %row_for_alias) {
    my $value_id = $value_id_for_row{$row_for_alias{$key}};
    die "Block alias '$key' has no reachable value\n" unless defined $value_id;
    $alias_value_id{$key} = $value_id;
}
my @alias_keys = sort keys %alias_value_id;

print <<'HEADER';
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;
import java.util.Arrays;

/*
 * Generated from Perl 5.44's pinned Unicode Character Database by
 * dev/tools/generate_perl_unicode_block_data.pl. Do not edit manually.
 *
 * Unicode data source copyright:
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in
 * the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 */
final class PerlUnicodeBlockData {
HEADER

print "    static final String UNICODE_VERSION = \"$expected_version\";\n";
print "    static final short INVALID = -1;\n";
print "    static final short NO_BLOCK = 0;\n\n";
print "    private static final String[] VALUE_NAMES = {\n";
for (my $i = 0; $i < @value_names; $i += 6) {
    my $end = $i + 5 < $#value_names ? $i + 5 : $#value_names;
    print "        ", join(', ', map { qq{\"$value_names[$_]\"} } $i .. $end), ",\n";
}
print "    };\n\n    private static final int[] RANGES = {\n";
for (my $i = 0; $i < @partition; $i += 3) {
    my $end = $i + 2 < $#partition ? $i + 2 : $#partition;
    my @items = map { sprintf("0x%X, 0x%X, %d,", @$_) }
        @partition[$i .. $end];
    print "        ", join(' ', @items), "\n";
}
print "    };\n\n    private static final String[] ALIAS_KEYS = {\n";
for (my $i = 0; $i < @alias_keys; $i += 6) {
    my $end = $i + 5 < $#alias_keys ? $i + 5 : $#alias_keys;
    print "        ", join(', ', map { qq{\"$alias_keys[$_]\"} } $i .. $end), ",\n";
}
print "    };\n\n    private static final short[] ALIAS_VALUE_IDS = {\n";
for (my $i = 0; $i < @alias_keys; $i += 20) {
    my $end = $i + 19 < $#alias_keys ? $i + 19 : $#alias_keys;
    print "        ", join(', ', map { $alias_value_id{$alias_keys[$_]} } $i .. $end), ",\n";
}
print <<'FOOTER';
    };

    private static final UnicodeSet[] SETS = buildSets();

    static int valueCount() {
        return VALUE_NAMES.length;
    }

    static int aliasCount() {
        return ALIAS_KEYS.length;
    }

    static int rangeCount() {
        return RANGES.length / 3;
    }

    static String canonicalValue(int valueId) {
        return VALUE_NAMES[valueId];
    }

    static UnicodeSet set(int valueId) {
        return SETS[valueId];
    }

    static UnicodeSet set(String valueAlias) {
        short valueId = value(valueAlias);
        return valueId == INVALID ? null : SETS[valueId];
    }

    static short value(String valueAlias) {
        String key = loose(valueAlias);
        int index = Arrays.binarySearch(ALIAS_KEYS, key);
        return index < 0 ? INVALID : ALIAS_VALUE_IDS[index];
    }

    static boolean isPropertyAlias(String alias) {
        boolean hasIsPrefix = alias != null && alias.startsWith("Is");
        String normalized = loose(hasIsPrefix ? alias.substring(2) : alias);
        return normalized.equals("blk") || normalized.equals("block");
    }

    private static UnicodeSet[] buildSets() {
        UnicodeSet[] sets = new UnicodeSet[VALUE_NAMES.length];
        for (int valueId = 0; valueId < sets.length; valueId++) {
            sets[valueId] = new UnicodeSet();
        }
        for (int offset = 0; offset < RANGES.length; offset += 3) {
            sets[RANGES[offset + 2]].add(RANGES[offset], RANGES[offset + 1]);
        }
        for (int valueId = 0; valueId < sets.length; valueId++) {
            sets[valueId].freeze();
        }
        return sets;
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

    private PerlUnicodeBlockData() {
    }
}
FOOTER
