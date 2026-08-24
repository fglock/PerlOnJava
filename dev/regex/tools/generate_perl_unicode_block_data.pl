#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin;
use lib File::Spec->catdir($FindBin::Bin, 'lib');
use PerlOnJava::UnicodeGenerator qw(
    emit_unicode_source_notices loose_name parse_range read_raw repo_root
    select_perl_root select_unicode_root trim verify_unicode_notice
);

binmode STDOUT, ':raw';

my $root = repo_root($FindBin::Bin);
my @required_sources = qw(version Blocks.txt PropertyAliases.txt PropValueAliases.txt);
my $unicore = select_unicode_root(
    repo_root => $root, version => 'current',
    required => \@required_sources);
my $unicode_version = read_raw(File::Spec->catfile($unicore, 'version'));
$unicode_version =~ s/\s+\z//;
die "Malformed current Unicode version '$unicode_version'\n"
    unless $unicode_version =~ /\A\d+\.\d+\.\d+\z/;
my @sources = (
    {
        name => "Blocks-$unicode_version.txt",
        path => File::Spec->catfile($unicore, 'Blocks.txt'),
        version => qr/^# Blocks-\Q$unicode_version\E\.txt$/m,
    },
    {
        name => "PropertyAliases-$unicode_version.txt",
        path => File::Spec->catfile($unicore, 'PropertyAliases.txt'),
        version => qr/^# PropertyAliases-\Q$unicode_version\E\.txt$/m,
    },
    {
        name => "PropertyValueAliases-$unicode_version.txt",
        path => File::Spec->catfile($unicore, 'PropValueAliases.txt'),
        version => qr/^# PropertyValueAliases-\Q$unicode_version\E\.txt$/m,
    },
);
for my $source (@sources) {
    $source->{text} = read_raw($source->{path});
    $source->{hash} = sha256_hex($source->{text});
    die "$source->{path} is inconsistent with Unicode $unicode_version\n"
        unless $source->{text} =~ $source->{version};
    verify_unicode_notice($source->{path}, $source->{text});
}
my ($blocks_source, $property_source, $value_source) = @sources;

my $perl_root = select_perl_root(
    repo_root => $root, unicode_root => $unicore, required => ['uni_keywords.h']);
my $keywords_path = File::Spec->catfile($perl_root, 'uni_keywords.h');
my $keywords_text = read_raw($keywords_path);
my $keywords_hash = sha256_hex($keywords_text);
die "$keywords_path is not a generated current-Perl Unicode keyword table\n"
    unless $keywords_text =~ /This file is built by regen\/mk_invlists\.pl/
        && $keywords_text =~ /generator script:\s*regen\/mk_invlists\.pl/;

sub loose { return loose_name(@_); }
sub range_from_text { return parse_range(@_); }

my ($property_path, $property_text) =
    ($property_source->{path}, $property_source->{text});
my @property_aliases;
for my $line (split /\n/, $property_text) {
    $line =~ s/#.*//;
    my @fields = map { trim($_) } split /;/, $line, -1;
    next unless @fields >= 2 && $fields[0] eq 'blk';
    @property_aliases = grep { length } @fields;
}
die "Unexpected Block aliases in $property_path: @property_aliases\n"
    unless join("\0", @property_aliases) eq join("\0", 'blk', 'Block');

my ($value_path, $value_text) = ($value_source->{path}, $value_source->{text});
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
die "Current Block aliases define no values\n" unless @value_rows;
my %exact_aliases;
my $alias_field_count = 0;
for my $row (@value_rows) {
    $alias_field_count += @$row;
    $exact_aliases{$_} = 1 for @$row;
}
die "Current Block aliases define no exact or loose names\n"
    unless $alias_field_count && keys(%exact_aliases) && keys(%row_for_alias);
my $no_block_row = $row_for_long{loose('No_Block')};
die "Missing No_Block aliases\n" unless defined $no_block_row;

my ($blocks_path, $blocks_text) = ($blocks_source->{path}, $blocks_source->{text});
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
die "Current Block data has no explicit ranges\n" unless @explicit;

my @value_names = ('No_Block');
my %value_id_for_row = ($no_block_row => 0);
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
}
die "Not every current Block value is reachable from Blocks.txt\n"
    unless @value_names == @value_rows && keys(%value_id_for_row) == @value_rows;

my @partition;
my $cursor = 0;
for my $range (@explicit) {
    push @partition, [$cursor, $range->[0] - 1, 0] if $cursor < $range->[0];
    push @partition, [@$range];
    $cursor = $range->[1] + 1;
}
push @partition, [$cursor, 0x10ffff, 0] if $cursor <= 0x10ffff;
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
die "Current Block partition has no No_Block ranges\n"
    unless $no_block_ranges && $no_block_count;

my %alias_value_id;
for my $key (keys %row_for_alias) {
    my $value_id = $value_id_for_row{$row_for_alias{$key}};
    die "Block alias '$key' has no reachable value\n" unless defined $value_id;
    $alias_value_id{$key} = $value_id;
}
my @alias_keys = sort keys %alias_value_id;

# Perl adds bare In... compatibility spellings that are not all expressible by
# simply prefixing an official Block value alias.  Derive them from the current
# generated keyword table by joining its C property token to a reachable
# block=/blk= spelling from the same table.
my (@keyword_rows, %block_row_for_token);
for my $line (split /\n/, $keywords_text) {
    next unless $line =~ /\b(-?UNI_[A-Z0-9_]+)\s*\}\s*\/\*\s*([^*]+?)\s*\*\//;
    my ($token, $keyword) = ($1, trim($2));
    next if $token =~ s/^-//;
    push @keyword_rows, [$token, $keyword];
    next unless $keyword =~ /\A(?:blk|block)=(.+)\z/;
    my $row = $row_for_alias{loose($1)};
    next unless defined $row;
    die "Perl keyword token $token maps to multiple Block values\n"
        if exists $block_row_for_token{$token}
            && $block_row_for_token{$token} != $row;
    $block_row_for_token{$token} = $row;
}
my %shortcut_value_id;
for my $keyword_row (@keyword_rows) {
    my ($token, $keyword) = @$keyword_row;
    next unless $keyword =~ /\Ain[^=]+\z/;
    my $row = $block_row_for_token{$token};
    next unless defined $row;
    my $value_id = $value_id_for_row{$row};
    die "Perl Block shortcut '$keyword' has no reachable value\n"
        unless defined $value_id;
    my $key = loose($keyword);
    die "Perl Block shortcut '$keyword' collides across values\n"
        if exists $shortcut_value_id{$key}
            && $shortcut_value_id{$key} != $value_id;
    $shortcut_value_id{$key} = $value_id;
}
die "Current Perl keyword table defines no bare In... Block shortcuts\n"
    unless keys %shortcut_value_id;
my @shortcut_keys = sort keys %shortcut_value_id;

print <<'HEADER';
/*
 * Generated from the current Perl checkout's Unicode Character Database and
 * uni_keywords.h. Do not edit manually.
 *
HEADER
emit_unicode_source_notices(\@sources);
print <<'HEADER_END';
 * Perl keyword-generator provenance:
 * uni_keywords.h is generated by Perl's regen/mk_invlists.pl.
 * Distributed under either the GNU General Public License or the Artistic License,
 * as specified by the Perl source-tree README.
 *
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;
import java.util.Arrays;

final class PerlUnicodeBlockData {
HEADER_END

print "    static final String UNICODE_VERSION = \"$unicode_version\";\n";
print "    static final String BLOCKS_SHA256 = \"$blocks_source->{hash}\";\n";
print "    static final String PROPERTY_ALIASES_SHA256 = \"$property_source->{hash}\";\n";
print "    static final String PROP_VALUE_ALIASES_SHA256 = \"$value_source->{hash}\";\n";
print "    static final String PERL_UNI_KEYWORDS_SHA256 = \"$keywords_hash\";\n";
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
print "    };\n\n    private static final String[] SHORTCUT_KEYS = {\n";
for (my $i = 0; $i < @shortcut_keys; $i += 6) {
    my $end = $i + 5 < $#shortcut_keys ? $i + 5 : $#shortcut_keys;
    print "        ", join(', ', map { qq{\"$shortcut_keys[$_]\"} } $i .. $end), ",\n";
}
print "    };\n\n    private static final short[] SHORTCUT_VALUE_IDS = {\n";
for (my $i = 0; $i < @shortcut_keys; $i += 20) {
    my $end = $i + 19 < $#shortcut_keys ? $i + 19 : $#shortcut_keys;
    print "        ", join(', ', map { $shortcut_value_id{$shortcut_keys[$_]} } $i .. $end), ",\n";
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

    static UnicodeSet shortcutSet(String alias) {
        String key = loose(alias);
        int index = Arrays.binarySearch(SHORTCUT_KEYS, key);
        return index < 0 ? null : SETS[SHORTCUT_VALUE_IDS[index]];
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
