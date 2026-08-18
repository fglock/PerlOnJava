#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin;

binmode STDOUT, ':raw';

my $expected_version = '17.0.0';
my @required_sources = (
    'version', File::Spec->catfile('extracted', 'DDecompositionType.txt'),
    'PropertyAliases.txt', 'PropValueAliases.txt',
);
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
    Decomposition_Type => [File::Spec->catfile($unicore, 'extracted', 'DDecompositionType.txt'),
        'f44e5ceaf40edc1fe06ea0404e8bebc7d356dcc38aac076543b6874008a06e3e'],
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
    $text =~ s/[\s_-]+//g;
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
    die "$path does not preserve the Unicode terms notice\n"
        unless $text =~ m{^# For terms of use and license, see https://www\.unicode\.org/terms_of_use\.html$}m;
}

my ($version_path, $version_text) = source_text('Version');
$version_text =~ s/\s+\z//;
die "Expected Unicode $expected_version, found '$version_text' in $version_path\n"
    unless $version_text eq $expected_version;

my ($data_path, $data_text) = source_text('Decomposition_Type');
die "$data_path is not pinned Unicode $expected_version data\n"
    unless $data_text =~ /^# DerivedDecompositionType-\Q$expected_version\E\.txt$/m;
verify_unicode_notice($data_path, $data_text);

my (@ranges, @missing);
for my $line (split /\n/, $data_text) {
    if ($line =~ /^#\s*\@missing:\s*([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z0-9_]+)/) {
        my ($first, $last) = range_from_text($1);
        push @missing, [$first, $last, $2];
        next;
    }
    next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z0-9_]+)/;
    my ($first, $last) = range_from_text($1);
    push @ranges, [$first, $last, $2];
}
die "No Decomposition_Type ranges found in $data_path\n" unless @ranges;
die "No ordered \@missing rules found in $data_path\n" unless @missing;
@ranges = sort { $a->[0] <=> $b->[0] } @ranges;
for my $index (1 .. $#ranges) {
    die sprintf("Overlapping Decomposition_Type ranges at U+%04X\n", $ranges[$index][0])
        if $ranges[$index][0] <= $ranges[$index - 1][1];
}

my ($property_path, $property_text) = source_text('Property_Aliases');
die "$property_path is not pinned Unicode $expected_version data\n"
    unless $property_text =~ /^# PropertyAliases-\Q$expected_version\E\.txt$/m;
verify_unicode_notice($property_path, $property_text);
my @property_aliases;
for my $line (split /\n/, $property_text) {
    $line =~ s/#.*//;
    my @fields = map { trim($_) } split /;/, $line, -1;
    next unless @fields >= 2 && $fields[0] eq 'dt';
    @property_aliases = grep { length } @fields;
}
die "Missing dt aliases in $property_path\n" unless @property_aliases;

my ($value_path, $value_text) = source_text('Property_Value_Aliases');
die "$value_path is not pinned Unicode $expected_version data\n"
    unless $value_text =~ /^# PropertyValueAliases-\Q$expected_version\E\.txt$/m;
verify_unicode_notice($value_path, $value_text);
my (@values, %value_index, %value_aliases);
for my $line (split /\n/, $value_text) {
    $line =~ s/#.*//;
    my @fields = map { trim($_) } split /;/, $line, -1;
    next unless @fields >= 3 && $fields[0] eq 'dt';
    my ($short, $canonical, @extra) = @fields[1 .. $#fields];
    if (!exists $value_index{$canonical}) {
        $value_index{$canonical} = scalar @values;
        push @values, $canonical;
    }
    my $id = $value_index{$canonical};
    for my $alias (grep { length } ($short, $canonical, @extra)) {
        my $normalized = loose($alias);
        die "Conflicting Decomposition_Type alias '$alias'\n"
            if exists $value_aliases{$normalized} && $value_aliases{$normalized} != $id;
        $value_aliases{$normalized} = $id;
    }
}
die "Expected 18 Decomposition_Type values, found " . scalar(@values) . "\n"
    unless @values == 18;

# Perl adds one valid union value beyond the Unicode property-value aliases.
# perl5/lib/unicore/mktables names it Non_Canon / Non_Canonical and defines it
# as the union of every non-canonical decomposition type.
my $non_canonical_id = scalar @values;
push @values, 'Non_Canonical';
$value_index{Non_Canonical} = $non_canonical_id;
$value_aliases{loose('Non_Canon')} = $non_canonical_id;
$value_aliases{loose('Non_Canonical')} = $non_canonical_id;

for my $range (@ranges, @missing) {
    die "Unknown Decomposition_Type value '$range->[2]'\n"
        unless exists $value_index{$range->[2]};
}

my %starts = (0 => 1);
for my $range (@ranges, @missing) {
    die "Invalid Unicode range U+" . sprintf('%X', $range->[0]) . "..U+"
        . sprintf('%X', $range->[1]) . "\n"
        if $range->[0] < 0 || $range->[1] > 0x10ffff || $range->[0] > $range->[1];
    $starts{$range->[0]} = 1;
    $starts{$range->[1] + 1} = 1 if $range->[1] < 0x10ffff;
}
my @starts = sort { $a <=> $b } keys %starts;

sub value_at {
    my ($code, $explicit, $defaults) = @_;
    my $value;
    # UAX #44 applies @missing rules in source order; a later matching rule wins.
    for my $range (@$defaults) {
        $value = $range->[2] if $range->[0] <= $code && $code <= $range->[1];
    }
    my ($low, $high) = (0, $#$explicit);
    while ($low <= $high) {
        my $middle = ($low + $high) >> 1;
        if ($explicit->[$middle][0] <= $code) {
            $low = $middle + 1;
        } else {
            $high = $middle - 1;
        }
    }
    $value = $explicit->[$high][2]
        if $high >= 0 && $code <= $explicit->[$high][1];
    die sprintf("No Decomposition_Type value covers U+%04X\n", $code)
        unless defined $value;
    return $value;
}

# Guard the ordering contract even though Unicode 17 currently has one rule.
my @ordered_missing_probe = (
    [0x0000, 0x10ffff, 'None'],
    [0x1000, 0x10ff, 'Compat'],
    [0x1080, 0x10af, 'Wide'],
);
my @explicit_probe = ([0x10a0, 0x10a0, 'Canonical']);
my @probe_codes = (0x0fff, 0x1000, 0x107f, 0x1080, 0x109f, 0x10a0, 0x10af, 0x10b0, 0x1100);
my @probe_expected = qw(None Compat Compat Wide Wide Canonical Wide Compat None);
for my $index (0 .. $#probe_codes) {
    die "Ordered \@missing rule self-check failed\n"
        unless value_at($probe_codes[$index], \@explicit_probe, \@ordered_missing_probe)
            eq $probe_expected[$index];
}

my (@coalesced_starts, @coalesced_values);
for my $code (@starts) {
    my $id = $value_index{value_at($code, \@ranges, \@missing)};
    next if @coalesced_values && $id == $coalesced_values[-1];
    push @coalesced_starts, $code;
    push @coalesced_values, $id;
}
die "Generated partition does not begin at U+0000\n"
    unless @coalesced_starts && $coalesced_starts[0] == 0;

my %constant_for;
for my $value (@values) {
    my $constant = uc $value;
    $constant =~ s/[^A-Z0-9]+/_/g;
    $constant_for{$value} = $constant;
}

print <<'HEADER';
package org.perlonjava.runtime.regex;

/*
 * Generated from Perl 5.44's pinned Unicode Character Database by
 * dev/tools/generate_perl_unicode_decomposition_type_data.pl. Do not edit manually.
 *
 * Unicode data source copyright:
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in
 * the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 */
final class PerlUnicodeDecompositionTypeData {
HEADER

print "    static final String UNICODE_VERSION = \"$expected_version\";\n";
for my $value (@values) {
    print "    static final byte $constant_for{$value} = $value_index{$value};\n";
}
print "    static final byte INVALID = -1;\n\n";
print "    private static final String[] CANONICAL_NAMES = {\n        ";
print join(', ', map { qq{"$_"} } @values);
print "\n    };\n\n";
print "    private static final int[] STARTS = {\n";
for (my $i = 0; $i < @coalesced_starts; $i += 10) {
    my $end = $i + 9 < $#coalesced_starts ? $i + 9 : $#coalesced_starts;
    print "        ", join(', ', map { sprintf '0x%X', $coalesced_starts[$_] } $i .. $end), ",\n";
}
print "    };\n\n    private static final byte[] VALUES = {\n";
for (my $i = 0; $i < @coalesced_values; $i += 18) {
    my $end = $i + 17 < $#coalesced_values ? $i + 17 : $#coalesced_values;
    print "        ", join(', ', @coalesced_values[$i .. $end]), ",\n";
}
print <<'METHODS';
    };

    static byte propertyOf(int codePoint) {
        if (codePoint < 0 || codePoint > 0x10ffff) {
            throw new IllegalArgumentException("Not a Unicode code point: " + codePoint);
        }
        int low = 0;
        int high = STARTS.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (STARTS[middle] <= codePoint) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return VALUES[high];
    }

    static int rangeCount() {
        return STARTS.length;
    }

    static int rangeStart(int index) {
        return STARTS[index];
    }

    static int rangeEnd(int index) {
        return index + 1 < STARTS.length ? STARTS[index + 1] - 1 : 0x10ffff;
    }

    static byte rangeValue(int index) {
        return VALUES[index];
    }

    static boolean isPropertyAlias(String alias) {
        boolean hasIsPrefix = alias != null && alias.startsWith("Is");
        String normalized = loose(hasIsPrefix ? alias.substring(2) : alias);
        switch (normalized) {
METHODS
for my $alias (sort { loose($a) cmp loose($b) } @property_aliases) {
    print '            case "', loose($alias), "\":\n";
}
print <<'PROPERTY_FOOTER';
                return true;
            default:
                return false;
        }
    }

    static byte valueForAlias(String alias) {
        String normalized = loose(alias);
        switch (normalized) {
PROPERTY_FOOTER
my %aliases_by_id;
for my $alias (sort keys %value_aliases) {
    push @{$aliases_by_id{$value_aliases{$alias}}}, $alias;
}
for my $id (0 .. $#values) {
    for my $alias (@{$aliases_by_id{$id}}) {
        print "            case \"$alias\":\n";
    }
    print "                return $constant_for{$values[$id]};\n";
}
print <<'FOOTER';
            default:
                return INVALID;
        }
    }

    static String canonicalValueName(byte value) {
        return value >= 0 && value < CANONICAL_NAMES.length
                ? CANONICAL_NAMES[value] : null;
    }

    static boolean matches(byte property, byte requested) {
        if (requested == NON_CANONICAL) {
            return property != CANONICAL && property != NONE;
        }
        return property == requested;
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

    private PerlUnicodeDecompositionTypeData() {
    }
}
FOOTER
