#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin;
use lib File::Spec->catdir($FindBin::Bin, 'lib');
use PerlOnJava::UnicodeGenerator qw(
    emit_unicode_source_notices loose_name parse_range read_raw repo_root
    select_unicode_root trim verify_unicode_notice
);

my $root = repo_root($FindBin::Bin);
my $unicore = select_unicode_root(
    repo_root => $root, version => 'current',
    required => [qw(version IdType.txt DCoreProperties.txt PropList.txt
        PropValueAliases.txt PropertyAliases.txt Unikemet.txt LineBreak.txt
        extracted/DBinaryProperties.txt emoji/emoji.txt
        auxiliary/GraphemeBreakProperty.txt auxiliary/WordBreakProperty.txt
        auxiliary/SentenceBreakProperty.txt)],
);
my $unicode_version = read_raw(File::Spec->catfile($unicore, 'version'));
$unicode_version =~ s/\s+\z//;
die "Malformed current Unicode version '$unicode_version'\n"
    unless $unicode_version =~ /\A\d+\.\d+\.\d+\z/;
my @sources = (
    ['IdType.txt', qr/^# Version:\s*\Q$unicode_version\E$/m],
    ['DCoreProperties.txt', qr/^# DerivedCoreProperties-\Q$unicode_version\E\.txt$/m],
    ['PropList.txt', qr/^# PropList-\Q$unicode_version\E\.txt$/m],
    ['PropValueAliases.txt', qr/^# PropertyValueAliases-\Q$unicode_version\E\.txt$/m],
    ['PropertyAliases.txt', qr/^# PropertyAliases-\Q$unicode_version\E\.txt$/m],
    ['Unikemet.txt', qr/^# Unikemet-\Q$unicode_version\E\.txt$/m],
    ['LineBreak.txt', qr/^# LineBreak-\Q$unicode_version\E\.txt$/m],
    ['extracted/DBinaryProperties.txt', qr/^# DerivedBinaryProperties-\Q$unicode_version\E\.txt$/m],
    ['emoji/emoji.txt', qr/^# Version:\s*\Q$unicode_version\E$/m],
    ['auxiliary/GraphemeBreakProperty.txt', qr/^# GraphemeBreakProperty-\Q$unicode_version\E\.txt$/m],
    ['auxiliary/WordBreakProperty.txt', qr/^# WordBreakProperty-\Q$unicode_version\E\.txt$/m],
    ['auxiliary/SentenceBreakProperty.txt', qr/^# SentenceBreakProperty-\Q$unicode_version\E\.txt$/m],
);
for my $source (@sources) {
    my ($name, $version_pattern) = @$source;
    my $path = File::Spec->catfile($unicore, split m{/}, $name);
    my $text = read_raw($path);
    die "$path is inconsistent with Unicode $unicode_version\n"
        unless $text =~ $version_pattern;
    verify_unicode_notice($path, $text);
    @$source = ($name, sha256_hex($text), $text);
}
my %text = map { $_->[0] => $_->[2] } @sources;

my %property = (
    GCB => { aliases => [qw(GCB Grapheme_Cluster_Break)], default => 'Other' },
    InCB => { aliases => [qw(InCB Indic_Conjunct_Break)], default => 'None' },
    IDTYPE => {
        aliases => [qw(ID_Type Identifier_Type)], default => 'Not_Character',
        values => [qw(Not_Character Deprecated Default_Ignorable Not_NFKC Not_XID
            Exclusion Obsolete Technical Uncommon_Use Limited_Use Inclusion Recommended)],
    },
    KEHCORE => { aliases => ['kEH_Core'], default => 'N', values => [qw(C L N)] },
    LB => { aliases => [qw(LB Line_Break)], default => 'Unknown' },
    WB => { aliases => [qw(WB Word_Break)], default => 'Other' },
    SB => { aliases => [qw(SB Sentence_Break)], default => 'Other' },
);
my %binary_property = (
    ALPHA => { aliases => [qw(Alphabetic Alpha)] },
    GRBASE => { aliases => [qw(Grapheme_Base Gr_Base)] },
    IDEO => { aliases => [qw(Ideographic Ideo)] },
    IDS => { aliases => [qw(ID_Start IDS)] },
    IDC => { aliases => [qw(ID_Continue IDC)] },
    XIDS => { aliases => [qw(XID_Start XIDS)] },
    XIDC => { aliases => [qw(XID_Continue XIDC)] },
    BIDIM => { aliases => [qw(Bidi_Mirrored Bidi_M)] },
);
my %binary_key_for_property = (
    Alphabetic => 'ALPHA',
    Grapheme_Base => 'GRBASE',
    Ideographic => 'IDEO',
    ID_Start => 'IDS',
    ID_Continue => 'IDC',
    XID_Start => 'XIDS',
    XID_Continue => 'XIDC',
    Bidi_Mirrored => 'BIDIM',
);
my %extra_binary_property = map { $_ => 1 } qw(
    Emoji Emoji_Component Emoji_Modifier Emoji_Modifier_Base Emoji_Presentation
    Modifier_Combining_Mark
);

for my $line (split /\n/, $text{'PropValueAliases.txt'}) {
    next if $line =~ /^\s*#/;
    $line =~ s/#.*$//;
    my @field = map { trim($_) } split /;/, $line;
    next unless @field >= 3 && ($field[0] eq 'GCB' || $field[0] eq 'InCB'
        || $field[0] eq 'lb' || $field[0] eq 'WB' || $field[0] eq 'SB');
    my $spec = $property{$field[0] eq 'lb' ? 'LB' : $field[0]};
    push @{$spec->{values}}, $field[2];
    push @{$spec->{short_values}}, $field[1];
    my $index = $#{$spec->{values}};
    $spec->{value_alias}{loose_name($_)} = $index for grep { length } @field[1 .. $#field];
}
for my $key (qw(IDTYPE KEHCORE)) {
    my $spec = $property{$key};
    $spec->{short_values} = [@{$spec->{values}}];
    for my $index (0 .. $#{$spec->{values}}) {
        $spec->{value_alias}{loose_name($spec->{values}[$index])} = $index;
    }
}
for my $spec (values %property) {
    $spec->{property_alias}{loose_name($_)} = 1 for @{$spec->{aliases}};
    my ($default) = grep {
        loose_name($spec->{values}[$_]) eq loose_name($spec->{default})
    } 0 .. $#{$spec->{values}};
    die "Missing default $spec->{default}\n" unless defined $default;
    $spec->{default_index} = $default;
    $spec->{ranges} = [map { [] } @{$spec->{values}}];
}

sub add_range {
    my ($key, $range, $value) = @_;
    my $spec = $property{$key};
    my $index = $spec->{value_alias}{loose_name($value)};
    die "$key unknown value '$value'\n" unless defined $index;
    push @{$spec->{ranges}[$index]}, [parse_range($range)];
}

for my $line (split /\n/, $text{'auxiliary/GraphemeBreakProperty.txt'}) {
    add_range('GCB', $1, $2) if $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z_]+)/;
}
for my $line (split /\n/, $text{'auxiliary/WordBreakProperty.txt'}) {
    add_range('WB', $1, $2) if $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z_]+)/;
}
for my $line (split /\n/, $text{'auxiliary/SentenceBreakProperty.txt'}) {
    add_range('SB', $1, $2) if $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z_]+)/;
}
for my $line (split /\n/, $text{'DCoreProperties.txt'}) {
    add_range('InCB', $1, $2) if $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*InCB\s*;\s*([A-Za-z_]+)/;
    if ($line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*(Alphabetic|Grapheme_Base|ID_Start|ID_Continue|XID_Start|XID_Continue)\b/) {
        my %key_for = (Alphabetic => 'ALPHA', Grapheme_Base => 'GRBASE', ID_Start => 'IDS', ID_Continue => 'IDC', XID_Start => 'XIDS', XID_Continue => 'XIDC');
        push @{$binary_property{$key_for{$2}}{ranges}}, [parse_range($1)];
    }
}
for my $line (split /\n/, $text{'IdType.txt'}) {
    next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([^#]+)/;
    my ($range, $values) = ($1, trim($2));
    add_range('IDTYPE', $range, $_) for split /\s+/, $values;
}
for my $line (split /\n/, $text{'Unikemet.txt'}) {
    add_range('KEHCORE', $1, $2) if $line =~ /^U\+([0-9A-F]+)\tkEH_Core\t([CL])$/;
}
my @line_break_explicit;
for my $line (split /\n/, $text{'LineBreak.txt'}) {
    next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z0-9_]+)/;
    push @line_break_explicit, [parse_range($1), $2];
}
my $line_break = $property{LB};
my $line_break_default = $line_break->{value_alias}{loose_name('XX')};
die "Line_Break aliases do not define XX\n" unless defined $line_break_default;
my @line_break_values = ($line_break_default) x 0x110000;
# UAX #14 defines these ranges as ID/PR when no explicit LineBreak.txt entry
# overrides them.  Applying explicit data afterwards retains assigned values.
for my $default ([0x3400, 0x4DBF, 'ID'], [0x4E00, 0x9FFF, 'ID'],
        [0xF900, 0xFAFF, 'ID'], [0x20000, 0x2FFFD, 'ID'],
        [0x30000, 0x3FFFD, 'ID'], [0x1F000, 0x1FAFF, 'ID'],
        [0x1FC00, 0x1FFFD, 'ID'], [0x20A0, 0x20CF, 'PR']) {
    my $index = $line_break->{value_alias}{loose_name($default->[2])};
    die "Line_Break aliases do not define $default->[2]\n" unless defined $index;
    $line_break_values[$_] = $index for $default->[0] .. $default->[1];
}
for my $entry (@line_break_explicit) {
    my ($start, $end, $value) = @$entry;
    my $index = $line_break->{value_alias}{loose_name($value)};
    die "Unknown Line_Break value '$value'\n" unless defined $index;
    $line_break_values[$_] = $index for $start .. $end;
}
my ($line_break_start, $line_break_value) = (0, $line_break_values[0]);
for my $code (1 .. 0x10FFFF) {
    next if $line_break_values[$code] == $line_break_value;
    push @{$line_break->{ranges}[$line_break_value]}, [$line_break_start, $code - 1];
    ($line_break_start, $line_break_value) = ($code, $line_break_values[$code]);
}
push @{$line_break->{ranges}[$line_break_value]}, [$line_break_start, 0x10FFFF];
$line_break->{complete} = 1;
for my $source (qw(DCoreProperties.txt PropList.txt extracted/DBinaryProperties.txt emoji/emoji.txt)) {
    for my $line (split /\n/, $text{$source}) {
        next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z0-9_]+)\s*(?:#|$)/;
        my ($range, $name) = ($1, $2);
        next unless exists $binary_key_for_property{$name}
                || $extra_binary_property{$name};
        my $key = $binary_key_for_property{$name};
        if (!defined $key) {
            $key = 'BIN_' . uc($name);
            $key =~ s/[^A-Z0-9]+/_/g;
            die "Binary-property generator key collision for '$name'\n"
                if exists $binary_property{$key};
            $binary_property{$key} = { aliases => [$name] };
            $binary_key_for_property{$name} = $key;
        }
        push @{$binary_property{$key}{ranges}}, [parse_range($range)];
    }
}
my @hex_ranges;
for my $line (split /\n/, $text{'PropList.txt'}) {
    push @hex_ranges, [parse_range($1)]
        if $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*Hex_Digit\b/;
    push @{$binary_property{IDEO}{ranges}}, [parse_range($1)]
        if $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*Ideographic\b/;
}

sub coalesce {
    my ($ranges) = @_;
    my @out;
    for my $range (sort { $a->[0] <=> $b->[0] || $a->[1] <=> $b->[1] } @$ranges) {
        if (@out && $range->[0] <= $out[-1][1] + 1) {
            $out[-1][1] = $range->[1] if $range->[1] > $out[-1][1];
        } else {
            push @out, [@$range];
        }
    }
    return \@out;
}
sub complement {
    my ($ranges) = @_;
    my $merged = coalesce($ranges);
    my @out;
    my $next = 0;
    for my $range (@$merged) {
        push @out, [$next, $range->[0] - 1] if $next < $range->[0];
        $next = $range->[1] + 1 if $next <= $range->[1];
    }
    push @out, [$next, 0x10FFFF] if $next <= 0x10FFFF;
    return \@out;
}
for my $spec (values %property) {
    next if $spec->{complete};
    my @explicit = map { @$_ } @{$spec->{ranges}};
    $spec->{ranges}[$spec->{default_index}] = complement(\@explicit);
    $spec->{ranges}[$_] = coalesce($spec->{ranges}[$_])
        for 0 .. $#{$spec->{ranges}};
}

sub emit_strings {
    my ($name, $values) = @_;
    print "    private static final String[] $name = {\n        ",
        join(', ', map { qq{"$_"} } @$values), "\n    };\n";
}
sub emit_pairs {
    my ($ranges, $indent) = @_;
    $indent //= '            ';
    for (my $i = 0; $i < @$ranges; $i += 4) {
        my $end = $i + 3 < $#$ranges ? $i + 3 : $#$ranges;
        print $indent, join(', ', map { sprintf '0x%X, 0x%X', @{$ranges->[$_]} } $i .. $end), ",\n";
    }
}
sub emit_property {
    my ($key) = @_;
    my $spec = $property{$key};
    my @value_alias = sort keys %{$spec->{value_alias}};
    my @property_alias = sort keys %{$spec->{property_alias}};
    emit_strings("${key}_VALUES", $spec->{values});
    emit_strings("${key}_SHORT_VALUES", $spec->{short_values});
    emit_strings("${key}_VALUE_ALIASES", \@value_alias);
    print "    private static final byte[] ${key}_VALUE_ALIAS_INDEX = {\n        ",
        join(', ', map { $spec->{value_alias}{$_} } @value_alias), "\n    };\n";
    emit_strings("${key}_PROPERTY_ALIASES", \@property_alias);
    for my $index (0 .. $#{$spec->{ranges}}) {
        my $ranges = $spec->{ranges}[$index];
        print "    private static int[] ${key}_ranges_$index() {\n",
            "        return new int[] {\n";
        emit_pairs($ranges);
        print "        };\n    }\n";
    }
    print "    private static final int[][] ${key}_RANGES = {\n        ",
        join(', ', map { "${key}_ranges_$_()" } 0 .. $#{$spec->{ranges}}),
        "\n    };\n";
    print "    private static final Property $key = new Property(\n",
        "            ${key}_VALUES, ${key}_SHORT_VALUES,\n",
        "            ${key}_VALUE_ALIASES, ${key}_VALUE_ALIAS_INDEX,\n",
        "            ${key}_PROPERTY_ALIASES, ${key}_RANGES);\n\n";
}

print <<'HEADER';
/*
 * Generated from the current Perl checkout's Unicode Character Database. Do not edit manually.
 *
HEADER
emit_unicode_source_notices([map { { name => $_->[0], text => $_->[2] } } @sources]);
print <<'JAVA';
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;

final class PerlUnicodeResidualPropertyData {
JAVA
print "    static final String UNICODE_VERSION = \"$unicode_version\";\n";
for my $source (@sources) {
    my $constant = uc $source->[0]; $constant =~ s/[^A-Z0-9]+/_/g;
    print "    static final String ${constant}_SHA256 = \"$source->[1]\";\n";
}
print "\n";
emit_property($_) for qw(GCB InCB IDTYPE KEHCORE LB WB SB);
print "    private static final int[] HEX_RANGES = {\n"; emit_pairs(\@hex_ranges, '        '); print "    };\n";
for my $key (sort keys %binary_property) {
    $binary_property{$key}{ranges} = coalesce($binary_property{$key}{ranges});
    print "    private static int[] ${key}_ranges() {\n        return new int[] {\n";
    emit_pairs($binary_property{$key}{ranges}, '        ');
    print "        };\n    }\n";
}
print <<'JAVA';
    private static final UnicodeSet HEX = buildSet(HEX_RANGES);

JAVA
for my $key (sort keys %binary_property) {
    print "    private static final UnicodeSet $key = buildSet(${key}_ranges());\n";
}
my @binary_aliases;
for my $key (sort keys %binary_property) {
    for my $alias (@{$binary_property{$key}{aliases}}) {
        push @binary_aliases, [loose_name($alias), $key];
    }
}
@binary_aliases = sort { $a->[0] cmp $b->[0] } @binary_aliases;
print "    private static final String[] BINARY_PROPERTY_ALIASES = {\n        ",
    join(', ', map { qq{\"$_->[0]\"} } @binary_aliases), "\n    };\n";
print "    private static final UnicodeSet[] BINARY_PROPERTY_SETS = {\n        ",
    join(', ', map { $_->[1] } @binary_aliases), "\n    };\n\n";
print <<'JAVA';

    static boolean isPropertyAlias(String alias) { return property(alias) != null; }
    static boolean isBinaryPropertyAlias(String alias) {
        String loose = loose(alias);
        return loose.equals("hex") || loose.equals("hexdigit")
                || java.util.Arrays.binarySearch(BINARY_PROPERTY_ALIASES, loose) >= 0;
    }
    static UnicodeSet binarySet(String alias) {
        String loose = loose(alias);
        if (loose.equals("hex") || loose.equals("hexdigit")) return HEX;
        int found = java.util.Arrays.binarySearch(BINARY_PROPERTY_ALIASES, loose);
        return found < 0 ? null : BINARY_PROPERTY_SETS[found];
    }
    static UnicodeSet valueSet(String propertyAlias, String valueAlias) {
        Property property = property(propertyAlias);
        return property == null ? null : property.valueSet(valueAlias);
    }
    static int valueCount(String propertyAlias) {
        Property property = property(propertyAlias);
        return property == null ? 0 : property.values.length;
    }
    static String value(String propertyAlias, int index) {
        Property property = property(propertyAlias);
        return property == null ? null : property.values[index];
    }
    static String shortValue(String propertyAlias, int index) {
        Property property = property(propertyAlias);
        return property == null ? null : property.shortValues[index];
    }
    private static Property property(String alias) {
        String loose = loose(alias);
        if (GCB.hasAlias(loose)) return GCB;
        if (InCB.hasAlias(loose)) return InCB;
        if (IDTYPE.hasAlias(loose)) return IDTYPE;
        if (KEHCORE.hasAlias(loose)) return KEHCORE;
        if (LB.hasAlias(loose)) return LB;
        if (WB.hasAlias(loose)) return WB;
        if (SB.hasAlias(loose)) return SB;
        return null;
    }
    private static String loose(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\t-\\r _-]+", "");
    }
    private static UnicodeSet buildSet(int[] ranges) {
        UnicodeSet set = new UnicodeSet();
        for (int index = 0; index < ranges.length; index += 2) {
            set.add(ranges[index], ranges[index + 1]);
        }
        return set.freeze();
    }
    private static final class Property {
        private final String[] values;
        private final String[] shortValues;
        private final String[] valueAliases;
        private final byte[] valueAliasIndex;
        private final String[] propertyAliases;
        private final int[][] ranges;
        private final UnicodeSet[] sets;
        private Property(String[] values, String[] shortValues,
                String[] valueAliases, byte[] valueAliasIndex,
                String[] propertyAliases, int[][] ranges) {
            this.values = values;
            this.shortValues = shortValues;
            this.valueAliases = valueAliases;
            this.valueAliasIndex = valueAliasIndex;
            this.propertyAliases = propertyAliases;
            this.ranges = ranges;
            this.sets = new UnicodeSet[values.length];
        }
        private boolean hasAlias(String alias) {
            return java.util.Arrays.binarySearch(propertyAliases, alias) >= 0;
        }
        private UnicodeSet valueSet(String alias) {
            int found = java.util.Arrays.binarySearch(valueAliases, loose(alias));
            if (found < 0) return null;
            int index = valueAliasIndex[found];
            UnicodeSet result = sets[index];
            if (result == null) sets[index] = result = buildSet(ranges[index]);
            return result;
        }
    }
    private PerlUnicodeResidualPropertyData() {}
}
JAVA
