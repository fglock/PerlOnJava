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
    { name => 'PropList-17.0.0.txt', path => File::Spec->catfile($unicore, 'PropList.txt'),
      hash => '130dcddcaadaf071008bdfce1e7743e04fdfbc910886f017d9f9ac931d8c64dd',
      version => qr/^# PropList-\Q$expected_version\E\.txt$/m },
    { name => 'Unikemet-17.0.0.txt', path => File::Spec->catfile($unicore, 'Unikemet.txt'),
      hash => '76a3081265e6eb673873f9c93d6f36062e82c7ed027c5c1a592accfbe48c20a5',
      version => qr/^# Unikemet-\Q$expected_version\E\.txt$/m },
    { name => 'PropertyValueAliases-17.0.0.txt', path => File::Spec->catfile($unicore, 'PropValueAliases.txt'),
      hash => '670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01',
      version => qr/^# PropertyValueAliases-\Q$expected_version\E\.txt$/m },
    { name => 'PropertyAliases-17.0.0.txt', path => File::Spec->catfile($unicore, 'PropertyAliases.txt'),
      hash => '4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb',
      version => qr/^# PropertyAliases-\Q$expected_version\E\.txt$/m },
);
my @properties = (
    { key => 'Hyphen', short => 'Hyphen', long => 'Hyphen', source => 'prop', deprecated => 1 },
    { key => 'kEH_NoMirror', short => 'kEH_NoMirror', long => 'kEH_NoMirror', source => 'unikemet', new => 1, contributory => 1 },
    { key => 'kEH_NoRotate', short => 'kEH_NoRotate', long => 'kEH_NoRotate', source => 'unikemet', new => 1, contributory => 1 },
    { key => 'ID_Compat_Math_Continue', short => 'ID_Compat_Math_Continue', long => 'ID_Compat_Math_Continue', source => 'prop', contributory => 1 },
    { key => 'ID_Compat_Math_Start', short => 'ID_Compat_Math_Start', long => 'ID_Compat_Math_Start', source => 'prop', contributory => 1 },
    { key => 'IDS_Unary_Operator', short => 'IDSU', long => 'IDS_Unary_Operator', source => 'prop', new => 1 },
    { key => 'Modifier_Combining_Mark', short => 'MCM', long => 'Modifier_Combining_Mark', source => 'prop', contributory => 1 },
);

sub read_source {
    my ($source) = @_;
    open my $fh, '<:raw', $source->{path} or die "Cannot read $source->{path}: $!\n";
    local $/; my $text = <$fh>; close $fh or die "Cannot close $source->{path}: $!\n";
    my $actual = sha256_hex($text);
    die "$source->{path} SHA-256 mismatch: expected $source->{hash}, found $actual\n"
        unless $actual eq $source->{hash};
    die "$source->{path} is not pinned Unicode $expected_version data\n"
        unless $text =~ $source->{version};
    $source->{text} = $text;
}
sub trim { my $x = shift; $x =~ s/^\s+|\s+$//g; $x }
sub loose { my $x = lc shift; $x =~ s/[\s_-]+//g; $x }
sub range { my ($x) = @_; my ($a, $b) = split /\.\./, $x; (hex($a), hex($b // $a)) }
read_source($_) for @sources;
open my $vf, '<', File::Spec->catfile($unicore, 'version') or die $!;
chomp(my $version = <$vf>); close $vf; die "Expected Unicode $expected_version, found $version\n" unless $version eq $expected_version;

my %by_long = map { $_->{long} => $_ } @properties;
for my $line (split /\n/, $sources[3]{text}) {
    next if $line =~ /^\s*#/; $line =~ s/#.*$//;
    my @f = map { trim($_) } split /;/, $line;
    next unless @f >= 2;
    for my $p (@properties) {
        next unless grep { $_ eq $p->{short} || $_ eq $p->{long} } @f;
        $p->{aliases}{loose($_)} = 1 for grep { length } @f;
    }
}
for my $p (@properties) {
    die "Missing aliases for $p->{long}\n"
        unless $p->{aliases}{loose($p->{short})} && $p->{aliases}{loose($p->{long})};
    $p->{ranges} = [];
}
for my $line (split /\n/, $sources[0]{text}) {
    next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z0-9_]+)/;
    my ($spec, $name) = ($1, $2);
    next unless exists $by_long{$name} && $by_long{$name}{source} eq 'prop';
    push @{$by_long{$name}{ranges}}, [range($spec)];
}
for my $line (split /\n/, $sources[1]{text}) {
    next unless $line =~ /^U\+([0-9A-F]+)\s+(kEH_No(?:Mirror|Rotate))\s+Y\s*$/;
    my ($code, $name) = (hex($1), $2);
    push @{$by_long{$name}{ranges}}, [$code, $code];
}
for my $p (@properties) {
    die "No positive data for $p->{long}\n" unless @{$p->{ranges}};
    my @merged;
    for my $r (sort { $a->[0] <=> $b->[0] } @{$p->{ranges}}) {
        if (@merged && $r->[0] <= $merged[-1][1] + 1) { $merged[-1][1] = $r->[1] if $r->[1] > $merged[-1][1] }
        else { push @merged, [@$r] }
    }
    $p->{ranges} = \@merged;
    $p->{code_points} += $_->[1] - $_->[0] + 1 for @merged;
}

print <<'HEAD';
/*
 * Generated from Perl 5.44's pinned Unicode Character Database. Do not edit manually.
 *
HEAD
for my $s (@sources) {
    print " * Source: $s->{name}\n";
    for my $line (split /\n/, $s->{text}) {
        next unless $line =~ /^# (?:©|Unicode and|the U\.S\.|For terms of use and license)/;
        $line =~ s/^# / * /; print "$line\n";
    }
    print " *\n";
}
print <<'JAVA';
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;

final class PerlUnicodeSpecializedBinaryData {
JAVA
print "    static final String UNICODE_VERSION = \"$version\";\n";
print "    static final String PROP_LIST_SHA256 = \"$sources[0]{hash}\";\n";
print "    static final String UNIKEMET_SHA256 = \"$sources[1]{hash}\";\n";
print "    static final String PROP_VALUE_ALIASES_SHA256 = \"$sources[2]{hash}\";\n";
print "    static final String PROPERTY_ALIASES_SHA256 = \"$sources[3]{hash}\";\n\n";
print "    private static final String[] SHORT_NAMES = {\n        ", join(', ', map { qq{"$_->{short}"} } @properties), "\n    };\n";
print "    private static final String[] LONG_NAMES = {\n        ", join(', ', map { qq{"$_->{long}"} } @properties), "\n    };\n";
print "    private static final boolean[] DEPRECATED = {\n        ", join(', ', map { $_->{deprecated} ? 'true' : 'false' } @properties), "\n    };\n";
print "    private static final boolean[] CONTRIBUTORY = {\n        ", join(', ', map { $_->{contributory} ? 'true' : 'false' } @properties), "\n    };\n";
print "    private static final boolean[] NEW_IN_UNICODE_17 = {\n        ", join(', ', map { $_->{new} ? 'true' : 'false' } @properties), "\n    };\n";
my (@aliases, @alias_index);
for my $i (0 .. $#properties) { for my $a (sort keys %{$properties[$i]{aliases}}) { push @aliases, $a; push @alias_index, $i } }
print "    private static final String[] PROPERTY_ALIASES = {\n        ", join(', ', map { qq{"$_"} } @aliases), "\n    };\n";
print "    private static final byte[] PROPERTY_ALIAS_INDEX = {\n        ", join(', ', @alias_index), "\n    };\n";
print "    private static final int[][] YES_RANGES = {\n";
for my $p (@properties) {
    print "        { ", join(', ', map { sprintf '0x%X, 0x%X', @$_ } @{$p->{ranges}}), " },\n";
}
print "    };\n";
print "    static final int[] POSITIVE_CODE_POINT_COUNTS = {\n        ", join(', ', map { $_->{code_points} } @properties), "\n    };\n";
print <<'FOOT';
    private static final UnicodeSet[] YES_SETS = buildYesSets();

    static boolean isPropertyAlias(String alias) { return propertyIndex(alias) >= 0; }
    static UnicodeSet yesSet(String alias) {
        int property = propertyIndex(alias);
        return property < 0 ? null : YES_SETS[property];
    }
    static UnicodeSet valueSet(String propertyAlias, String valueAlias) {
        int property = propertyIndex(propertyAlias);
        Boolean yes = binaryValue(valueAlias);
        if (property < 0 || yes == null) return null;
        return yes ? YES_SETS[property]
                : new UnicodeSet(0, 0x10FFFF).removeAll(YES_SETS[property]).freeze();
    }
    static String shortName(String alias) {
        int property = propertyIndex(alias); return property < 0 ? null : SHORT_NAMES[property];
    }
    static String canonicalName(String alias) {
        int property = propertyIndex(alias); return property < 0 ? null : LONG_NAMES[property];
    }
    static boolean isDeprecated(String alias) {
        int property = propertyIndex(alias); return property >= 0 && DEPRECATED[property];
    }
    static boolean isContributory(String alias) {
        int property = propertyIndex(alias); return property >= 0 && CONTRIBUTORY[property];
    }
    static boolean isNewInUnicode17(String alias) {
        int property = propertyIndex(alias); return property >= 0 && NEW_IN_UNICODE_17[property];
    }
    static String[] canonicalNames() { return LONG_NAMES.clone(); }

    private static int propertyIndex(String alias) {
        String name = looseName(alias); if (name == null) return -1;
        for (int i = 0; i < PROPERTY_ALIASES.length; i++)
            if (PROPERTY_ALIASES[i].equals(name)) return PROPERTY_ALIAS_INDEX[i];
        return -1;
    }
    private static Boolean binaryValue(String alias) {
        String value = looseName(alias); if (value == null) return null;
        if (value.equals("y") || value.equals("yes") || value.equals("t") || value.equals("true")) return true;
        if (value.equals("n") || value.equals("no") || value.equals("f") || value.equals("false")) return false;
        return null;
    }
    private static String looseName(String name) {
        if (name == null) return null;
        StringBuilder loose = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '-' || Character.isWhitespace(c)) continue;
            loose.append(Character.toLowerCase(c));
        }
        return loose.toString();
    }
    private static UnicodeSet[] buildYesSets() {
        UnicodeSet[] sets = new UnicodeSet[YES_RANGES.length];
        for (int p = 0; p < sets.length; p++) {
            sets[p] = new UnicodeSet();
            for (int i = 0; i < YES_RANGES[p].length; i += 2)
                sets[p].add(YES_RANGES[p][i], YES_RANGES[p][i + 1]);
            sets[p].freeze();
        }
        return sets;
    }
    private PerlUnicodeSpecializedBinaryData() {}
}
FOOT
