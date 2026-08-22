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
        PropValueAliases.txt PropertyAliases.txt Unikemet.txt
        auxiliary/GraphemeBreakProperty.txt)],
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
    ['auxiliary/GraphemeBreakProperty.txt', qr/^# GraphemeBreakProperty-\Q$unicode_version\E\.txt$/m],
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
);

for my $line (split /\n/, $text{'PropValueAliases.txt'}) {
    next if $line =~ /^\s*#/;
    $line =~ s/#.*$//;
    my @field = map { trim($_) } split /;/, $line;
    next unless @field >= 3 && ($field[0] eq 'GCB' || $field[0] eq 'InCB');
    my $spec = $property{$field[0]};
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
for my $line (split /\n/, $text{'DCoreProperties.txt'}) {
    add_range('InCB', $1, $2) if $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*InCB\s*;\s*([A-Za-z_]+)/;
}
for my $line (split /\n/, $text{'IdType.txt'}) {
    next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([^#]+)/;
    my ($range, $values) = ($1, trim($2));
    add_range('IDTYPE', $range, $_) for split /\s+/, $values;
}
for my $line (split /\n/, $text{'Unikemet.txt'}) {
    add_range('KEHCORE', $1, $2) if $line =~ /^U\+([0-9A-F]+)\tkEH_Core\t([CL])$/;
}
my @hex_ranges;
for my $line (split /\n/, $text{'PropList.txt'}) {
    push @hex_ranges, [parse_range($1)]
        if $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*Hex_Digit\b/;
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
emit_property($_) for qw(GCB InCB IDTYPE KEHCORE);
print "    private static final int[] HEX_RANGES = {\n"; emit_pairs(\@hex_ranges, '        '); print "    };\n";
print <<'JAVA';
    private static final UnicodeSet HEX = buildSet(HEX_RANGES);

    static boolean isPropertyAlias(String alias) { return property(alias) != null; }
    static boolean isBinaryPropertyAlias(String alias) {
        String loose = loose(alias);
        return loose.equals("hex") || loose.equals("hexdigit");
    }
    static UnicodeSet binarySet(String alias) {
        return isBinaryPropertyAlias(alias) ? HEX : null;
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
