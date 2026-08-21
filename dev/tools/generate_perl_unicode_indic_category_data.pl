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
    required => [qw(version IndicSyllabicCategory.txt
        IndicPositionalCategory.txt PropValueAliases.txt PropertyAliases.txt)],
);
my @sources = (
    {
        name => 'IndicSyllabicCategory-17.0.0.txt',
        path => File::Spec->catfile($unicore, 'IndicSyllabicCategory.txt'),
        hash => '3fc122f4cf58b0c19268d5f810263b04ab4e1e67743386ec0e0ada9c76aec5be',
        version => qr/^# IndicSyllabicCategory-\Q$expected_version\E\.txt$/m,
    },
    {
        name => 'IndicPositionalCategory-17.0.0.txt',
        path => File::Spec->catfile($unicore, 'IndicPositionalCategory.txt'),
        hash => '68cedc29a7e57f984d90fe2c7712f2e6d0c717e253db219607daea8997d6c480',
        version => qr/^# IndicPositionalCategory-\Q$expected_version\E\.txt$/m,
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

for my $source (@sources) {
    $source->{text} = read_pinned_source(
        path => $source->{path},
        sha256 => $source->{hash},
        version_pattern => $source->{version},
        unicode_version => $expected_version,
    );
}

my $unicode_version = read_unicode_version(
    path => File::Spec->catfile($unicore, 'version'), expected => $expected_version);

my %property = (
    InSC => {
        java => 'INSC',
        long => 'Indic_Syllabic_Category',
        source => $sources[0],
        expected_values => 37,
        expected_ranges => 967,
        default => 'Other',
        short_values => [],
        long_values => [],
        alias_index => {},
        property_aliases => {},
        ranges => [],
    },
    InPC => {
        java => 'INPC',
        long => 'Indic_Positional_Category',
        source => $sources[1],
        expected_values => 16,
        expected_ranges => 653,
        default => 'Not_Applicable',
        short_values => [],
        long_values => [],
        alias_index => {},
        property_aliases => {},
        ranges => [],
    },
);

for my $line (split /\n/, $sources[2]{text}) {
    next if $line =~ /^\s*#/;
    $line =~ s/#.*$//;
    my @fields = map { trim($_) } split /;/, $line;
    next unless @fields >= 3 && exists $property{$fields[0]};
    my $spec = $property{$fields[0]};
    my $index = scalar @{$spec->{short_values}};
    push @{$spec->{short_values}}, $fields[1];
    push @{$spec->{long_values}}, $fields[2];
    for my $alias (@fields[1 .. $#fields]) {
        next unless length $alias;
        my $loose = loose_name($alias);
        die "$fields[0] value alias collision for '$alias'\n"
            if exists $spec->{alias_index}{$loose}
                && $spec->{alias_index}{$loose} != $index;
        $spec->{alias_index}{$loose} = $index;
    }
}

for my $line (split /\n/, $sources[3]{text}) {
    next if $line =~ /^\s*#/;
    $line =~ s/#.*$//;
    my @fields = map { trim($_) } split /;/, $line;
    next unless @fields >= 2;
    for my $short (keys %property) {
        my $spec = $property{$short};
        next unless grep { $_ eq $short || $_ eq $spec->{long} } @fields;
        $spec->{property_aliases}{loose_name($_)} = 1
            for grep { length } @fields;
    }
}

for my $short (qw(InSC InPC)) {
    my $spec = $property{$short};
    die "$short expected $spec->{expected_values} values, found "
            . scalar(@{$spec->{short_values}}) . "\n"
        unless @{$spec->{short_values}} == $spec->{expected_values};
    die "$short property aliases missing\n"
        unless $spec->{property_aliases}{loose_name($short)}
            && $spec->{property_aliases}{loose_name($spec->{long})};

    my ($default_index) = grep {
        loose_name($spec->{long_values}[$_]) eq loose_name($spec->{default})
    } 0 .. $#{$spec->{long_values}};
    die "$short default $spec->{default} is absent from aliases\n"
        unless defined $default_index;
    $spec->{default_index} = $default_index;

    my $declared_default;
    for my $line (split /\n/, $spec->{source}{text}) {
        if ($line =~ /^#\s*\@missing:\s*0000\.\.10FFFF\s*;\s*([A-Za-z_]+)/) {
            $declared_default = $1;
            next;
        }
        next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z_]+)/;
        my ($start, $end) = parse_range($1);
        my $index = $spec->{alias_index}{loose_name($2)};
        die "$short unknown explicit value '$2'\n" unless defined $index;
        push @{$spec->{ranges}}, [$start, $end, $index];
    }
    die "$short expected default $spec->{default}, found "
            . (defined $declared_default ? $declared_default : '<none>') . "\n"
        unless defined $declared_default
            && loose_name($declared_default) eq loose_name($spec->{default});
    die "$short expected $spec->{expected_ranges} ranges, found "
            . scalar(@{$spec->{ranges}}) . "\n"
        unless @{$spec->{ranges}} == $spec->{expected_ranges};
}

sub emit_string_array {
    my ($name, $values) = @_;
    print "    private static final String[] $name = {\n        ";
    print join(', ', map { qq{"$_"} } @$values);
    print "\n    };\n";
}

sub emit_property {
    my ($short) = @_;
    my $spec = $property{$short};
    my @aliases = sort keys %{$spec->{alias_index}};
    my @property_aliases = sort keys %{$spec->{property_aliases}};
    my $prefix = $spec->{java};

    emit_string_array("${prefix}_SHORT_VALUES", $spec->{short_values});
    emit_string_array("${prefix}_LONG_VALUES", $spec->{long_values});
    emit_string_array("${prefix}_VALUE_ALIASES", \@aliases);
    print "    private static final byte[] ${prefix}_VALUE_ALIAS_INDEX = {\n        ";
    print join(', ', map { $spec->{alias_index}{$_} } @aliases);
    print "\n    };\n";
    emit_string_array("${prefix}_PROPERTY_ALIASES", \@property_aliases);
    print "    private static final int[] ${prefix}_RANGES = {\n";
    emit_java_range_triples($spec->{ranges});
    print "    };\n";
    print "    private static final Property $prefix = new Property(\n";
    print "            ${prefix}_SHORT_VALUES, ${prefix}_LONG_VALUES,\n";
    print "            ${prefix}_VALUE_ALIASES, ${prefix}_VALUE_ALIAS_INDEX,\n";
    print "            ${prefix}_PROPERTY_ALIASES, ${prefix}_RANGES,\n";
    print "            $spec->{default_index});\n\n";
}

print <<'HEADER';
/*
 * Generated from Perl 5.44's current Unicode Character Database. Do not edit manually.
 *
HEADER
emit_unicode_source_notices(\@sources);
print <<'HEADER_END';
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;

final class PerlUnicodeIndicCategoryData {
HEADER_END

print "    static final String UNICODE_VERSION = \"$unicode_version\";\n";
print "    static final String INDIC_SYLLABIC_CATEGORY_SHA256 = \"$sources[0]{hash}\";\n";
print "    static final String INDIC_POSITIONAL_CATEGORY_SHA256 = \"$sources[1]{hash}\";\n";
print "    static final String PROP_VALUE_ALIASES_SHA256 = \"$sources[2]{hash}\";\n";
print "    static final String PROPERTY_ALIASES_SHA256 = \"$sources[3]{hash}\";\n\n";

emit_property($_) for qw(InSC InPC);

print <<'FOOTER';
    static boolean isPropertyAlias(String alias) {
        return property(alias) != null;
    }

    static UnicodeSet valueSet(String propertyAlias, String valueAlias) {
        Property property = property(propertyAlias);
        return property == null ? null : property.valueSet(valueAlias);
    }

    static String canonicalValue(String propertyAlias, String valueAlias) {
        Property property = property(propertyAlias);
        return property == null ? null : property.canonicalValue(valueAlias);
    }

    static String[] canonicalValues(String propertyAlias) {
        Property property = property(propertyAlias);
        return property == null ? null : property.longValues.clone();
    }

    static String defaultValue(String propertyAlias) {
        Property property = property(propertyAlias);
        return property == null ? null : property.longValues[property.defaultIndex];
    }

    static int valueCount(String propertyAlias) {
        Property property = property(propertyAlias);
        return property == null ? 0 : property.longValues.length;
    }

    static String shortValue(String propertyAlias, int index) {
        Property property = property(propertyAlias);
        return property == null || index < 0 || index >= property.shortValues.length
                ? null : property.shortValues[index];
    }

    static String canonicalValue(String propertyAlias, int index) {
        Property property = property(propertyAlias);
        return property == null || index < 0 || index >= property.longValues.length
                ? null : property.longValues[index];
    }

    static UnicodeSet valueSet(String propertyAlias, int index) {
        Property property = property(propertyAlias);
        return property == null || index < 0 || index >= property.valueSets.length
                ? null : property.valueSets[index];
    }

    private static Property property(String alias) {
        String loose = looseName(alias);
        if (loose == null) return null;
        if (INSC.hasPropertyAlias(loose)) return INSC;
        if (INPC.hasPropertyAlias(loose)) return INPC;
        return null;
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

    private static final class Property {
        private final String[] shortValues;
        private final String[] longValues;
        private final String[] valueAliases;
        private final byte[] valueAliasIndex;
        private final String[] propertyAliases;
        private final int[] ranges;
        private final int defaultIndex;
        private final UnicodeSet[] valueSets;

        private Property(String[] shortValues, String[] longValues,
                         String[] valueAliases, byte[] valueAliasIndex,
                         String[] propertyAliases, int[] ranges,
                         int defaultIndex) {
            this.shortValues = shortValues;
            this.longValues = longValues;
            this.valueAliases = valueAliases;
            this.valueAliasIndex = valueAliasIndex;
            this.propertyAliases = propertyAliases;
            this.ranges = ranges;
            this.defaultIndex = defaultIndex;
            this.valueSets = buildValueSets();
        }

        private boolean hasPropertyAlias(String loose) {
            for (String alias : propertyAliases) {
                if (alias.equals(loose)) return true;
            }
            return false;
        }

        private UnicodeSet valueSet(String alias) {
            int index = valueIndex(alias);
            return index < 0 ? null : valueSets[index];
        }

        private String canonicalValue(String alias) {
            int index = valueIndex(alias);
            return index < 0 ? null : longValues[index];
        }

        private int valueIndex(String alias) {
            String loose = looseName(alias);
            if (loose == null) return -1;
            for (int i = 0; i < valueAliases.length; i++) {
                if (valueAliases[i].equals(loose)) return valueAliasIndex[i];
            }
            return -1;
        }

        private UnicodeSet[] buildValueSets() {
            UnicodeSet[] sets = new UnicodeSet[longValues.length];
            for (int i = 0; i < sets.length; i++) sets[i] = new UnicodeSet();
            sets[defaultIndex].add(0, 0x10FFFF);
            for (int i = 0; i < ranges.length; i += 3) {
                int valueIndex = ranges[i + 2];
                if (valueIndex != defaultIndex) {
                    sets[defaultIndex].remove(ranges[i], ranges[i + 1]);
                    sets[valueIndex].add(ranges[i], ranges[i + 1]);
                }
            }
            for (UnicodeSet set : sets) set.freeze();
            return sets;
        }
    }

    private PerlUnicodeIndicCategoryData() {
    }
}
FOOTER
