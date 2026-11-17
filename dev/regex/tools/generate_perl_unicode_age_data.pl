#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin;
use lib File::Spec->catdir($FindBin::Bin, 'lib');
use PerlOnJava::UnicodeGenerator qw(
    emit_java_range_triples emit_unicode_source_notices loose_name
    perl_language_version read_pinned_source read_unicode_version
    repo_root select_perl_root select_unicode_root trim verify_unicode_notice
);

my $expected_unicode_version = $ENV{PERLONJAVA_UNICODE_VERSION} // '17.0.0';
my $root = repo_root($FindBin::Bin);
my $unicode_root = select_unicode_root(
    repo_root => $root, version => $expected_unicode_version,
    required => [qw(version DAge.txt PropValueAliases.txt)]);
my $perl_root = select_perl_root(
    repo_root => $root, unicode_root => $unicode_root, required => ['patchlevel.h']);
my $perl_version = perl_language_version(root => $perl_root);

my $unicode_version = read_unicode_version(
    path => File::Spec->catfile($unicode_root, 'version'),
    expected => $expected_unicode_version,
    sha256 => '8c30575264b2772c7a69c5bb6069a28f0e0a7a0df735871bde2d99ee674316ac');

my $age_path = File::Spec->catfile($unicode_root, 'DAge.txt');
my $age_text = read_pinned_source(
    path => $age_path,
    sha256 => 'f8ecdf768bdc210f201abd271d9bc587825618a86a7046a8146cc816393f1998',
    version_pattern => qr/^# DerivedAge-\Q$expected_unicode_version\E\.txt$/m,
    unicode_version => $expected_unicode_version);
verify_unicode_notice($age_path, $age_text);

my $aliases_path = File::Spec->catfile($unicode_root, 'PropValueAliases.txt');
my $aliases_text = read_pinned_source(
    path => $aliases_path,
    sha256 => '06c4c8eaf7b0bf34abe73b113da1215bd784ac254d4c223600b90267caa4bbbd',
    version_pattern => qr/^# PropertyValueAliases-\Q$expected_unicode_version\E\.txt$/m,
    unicode_version => $expected_unicode_version);
verify_unicode_notice($aliases_path, $aliases_text);
my $aliases_sha256 = sha256_hex($aliases_text);
my (@versions, %version_index, @ranges);
for (split /\n/, $age_text) {
    next unless /^([0-9A-F]+)(?:\.\.([0-9A-F]+))?\s*;\s*([0-9]+\.[0-9]+)/;
    my ($start, $end, $version) = (hex($1), defined($2) ? hex($2) : hex($1), $3);
    if (!exists $version_index{$version}) {
        $version_index{$version} = scalar @versions;
        push @versions, $version;
    }
    if (@ranges && $ranges[-1][2] == $version_index{$version}
            && $ranges[-1][1] + 1 == $start) {
        $ranges[-1][1] = $end;
    } else {
        push @ranges, [$start, $end, $version_index{$version}];
    }
}

my ($expected_age) = $expected_unicode_version =~ /\A(\d+\.\d+)\.\d+\z/;
die "Age data does not end at Unicode $expected_age\n"
    unless @versions && $versions[-1] eq $expected_age;

my (@wildcard_aliases, @wildcard_alias_index, @unassigned_aliases);
my (%wildcard_alias_index, %loose_alias_index, %aliases_for_version);
for my $line (split /\n/, $aliases_text) {
    next if $line =~ /^\s*#/;
    $line =~ s/#.*$//;
    my @fields = map { trim($_) } split /;/, $line;
    next unless @fields >= 3 && lc($fields[0]) eq 'age';

    if (uc($fields[1]) eq 'NA') {
        @unassigned_aliases = grep { length } @fields[1 .. $#fields];
        next;
    }
    my $index = $version_index{$fields[1]};
    die "Age alias row has no generated version '$fields[1]'\n"
        unless defined $index;
    for my $alias (grep { length } @fields[1 .. $#fields]) {
        my $loose = loose_name($alias);
        die "Age wildcard alias collision for '$alias'\n"
            if exists $wildcard_alias_index{$alias}
                && $wildcard_alias_index{$alias} != $index;
        die "Age loose alias collision for '$alias'\n"
            if exists $loose_alias_index{$loose}
                && $loose_alias_index{$loose} != $index;
        $wildcard_alias_index{$alias} = $index;
        $loose_alias_index{$loose} = $index;
        $aliases_for_version{$index} = 1;
    }
}
die "Age PropertyValueAliases.txt has no NA/Unassigned aliases\n"
    unless @unassigned_aliases >= 2;
die "Age aliases are missing a generated version\n"
    if grep { !$aliases_for_version{$_} } 0 .. $#versions;
@wildcard_aliases = sort keys %wildcard_alias_index;
@wildcard_alias_index = map { $wildcard_alias_index{$_} } @wildcard_aliases;

print "/*\n";
print " * Generated from hash-verified Unicode Character Database sources in the\n";
print " * selected current Perl $perl_version checkout. Do not edit manually.\n";
print " *\n";
emit_unicode_source_notices([
    {name => "DerivedAge-$unicode_version.txt", path => $age_path,
        text => $age_text},
    {name => "PropertyValueAliases-$unicode_version.txt", path => $aliases_path,
        text => $aliases_text},
]);
print " */\n";
print <<'HEADER';
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;

final class PerlUnicodeAgeData {
HEADER

print "    static final String UNICODE_VERSION = \"$unicode_version\";\n";
print "    private static final String[] VERSIONS = {\n        ";
print join(', ', map { qq{"$_"} } @versions);
print "\n    };\n\n";
print "    static final String PROP_VALUE_ALIASES_SHA256 = \"$aliases_sha256\";\n";
print "    private static final String[] VALUE_ALIASES = {\n        ";
my @loose_aliases = sort keys %loose_alias_index;
print join(', ', map { qq{"$_"} } @loose_aliases);
print "\n    };\n";
print "    private static final byte[] VALUE_ALIAS_INDEX = {\n        ";
print join(', ', map { $loose_alias_index{$_} } @loose_aliases);
print "\n    };\n";
print "    private static final String[] WILDCARD_ALIASES = {\n        ";
print join(', ', map { qq{"$_"} } @wildcard_aliases);
print "\n    };\n";
print "    private static final byte[] WILDCARD_ALIAS_INDEX = {\n        ";
print join(', ', @wildcard_alias_index);
print "\n    };\n";
print "    private static final String[] UNASSIGNED_ALIASES = {\n        ";
print join(', ', map { qq{"$_"} } @unassigned_aliases);
print "\n    };\n\n";
print "    private static final int[] RANGES = {\n";
emit_java_range_triples(\@ranges);
print <<'FOOTER';
    };

    private static final UnicodeSet[] EXACT_SETS = buildExactSets();
    private static final UnicodeSet[] CUMULATIVE_SETS = buildCumulativeSets();
    private static final UnicodeSet UNASSIGNED = new UnicodeSet(0, 0x10ffff)
            .removeAll(CUMULATIVE_SETS[CUMULATIVE_SETS.length - 1]).freeze();

    static UnicodeSet exactSet(String version) {
        int versionIndex = versionIndex(version);
        return versionIndex < 0 ? null : EXACT_SETS[versionIndex];
    }

    static UnicodeSet cumulativeSet(String version) {
        int versionIndex = versionIndex(version);
        return versionIndex < 0 ? null : CUMULATIVE_SETS[versionIndex];
    }

    static UnicodeSet unassignedSet() {
        return UNASSIGNED;
    }

    static String canonicalVersion(String alias) {
        int index = valueIndex(alias);
        return index < 0 ? null : VERSIONS[index];
    }

    static String[] wildcardAliases(int index) {
        if (index < 0 || index >= VERSIONS.length) return new String[0];
        java.util.List<String> aliases = new java.util.ArrayList<>();
        for (int i = 0; i < WILDCARD_ALIAS_INDEX.length; i++) {
            if (WILDCARD_ALIAS_INDEX[i] == index) aliases.add(WILDCARD_ALIASES[i]);
        }
        return aliases.toArray(String[]::new);
    }

    static String[] unassignedAliases() {
        return UNASSIGNED_ALIASES.clone();
    }

    static int valueCount() {
        return VERSIONS.length;
    }

    static String versionAt(int index) {
        return VERSIONS[index];
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

    private static UnicodeSet[] buildExactSets() {
        UnicodeSet[] sets = new UnicodeSet[VERSIONS.length];
        for (int i = 0; i < sets.length; i++) sets[i] = new UnicodeSet();
        for (int i = 0; i < RANGES.length; i += 3) {
            sets[RANGES[i + 2]].add(RANGES[i], RANGES[i + 1]);
        }
        for (UnicodeSet set : sets) set.freeze();
        return sets;
    }

    private static UnicodeSet[] buildCumulativeSets() {
        UnicodeSet[] sets = new UnicodeSet[VERSIONS.length];
        UnicodeSet running = new UnicodeSet();
        for (int i = 0; i < sets.length; i++) {
            running.addAll(EXACT_SETS[i]);
            sets[i] = new UnicodeSet(running).freeze();
        }
        return sets;
    }

    private static int versionIndex(String version) {
        return valueIndex(version);
    }

    private PerlUnicodeAgeData() {
    }
}
FOOTER
