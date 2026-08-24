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
    required => [qw(version IndicSyllabicCategory.txt
        IndicPositionalCategory.txt PropValueAliases.txt PropertyAliases.txt)],
);
my $unicode_version = read_raw(File::Spec->catfile($unicore, 'version'));
$unicode_version =~ s/\s+\z//;
die "Malformed current Unicode version '$unicode_version'\n"
    unless $unicode_version =~ /\A\d+\.\d+\.\d+\z/;
my @sources = (
    {
        name => "IndicSyllabicCategory-$unicode_version.txt",
        path => File::Spec->catfile($unicore, 'IndicSyllabicCategory.txt'),
        version => qr/^# IndicSyllabicCategory-\Q$unicode_version\E\.txt$/m,
    },
    {
        name => "IndicPositionalCategory-$unicode_version.txt",
        path => File::Spec->catfile($unicore, 'IndicPositionalCategory.txt'),
        version => qr/^# IndicPositionalCategory-\Q$unicode_version\E\.txt$/m,
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

my %property = (
    InSC => {
        java => 'INSC',
        long => 'Indic_Syllabic_Category',
        source => $sources[0],
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
    die "$short defines no values\n" unless @{$spec->{short_values}};
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
    die "$short defines no explicit ranges\n" unless @{$spec->{ranges}};
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
 * Generated from the current Perl checkout's Unicode Character Database. Do not edit manually.
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
