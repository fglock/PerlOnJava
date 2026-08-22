package PerlOnJava::UnicodeGenerator;

use strict;
use warnings;
use Exporter qw(import);
use Digest::SHA qw(sha256_hex);
use File::Spec;

our @EXPORT_OK = qw(
    repo_root
    select_unicode_root
    select_perl_root
    read_raw
    read_pinned_source
    read_unicode_version
    perl_language_version
    trim
    loose_name
    parse_range
    verify_unicode_notice
    unicode_notice_lines
    emit_java_range_triples
    emit_unicode_source_notices
);

sub repo_root {
    my ($tools_dir) = @_;
    return File::Spec->rel2abs(File::Spec->catdir($tools_dir, '..', '..'));
}

sub select_unicode_root {
    my (%args) = @_;
    my $root = $args{repo_root} // die "repo_root is required\n";
    my $version = $args{version} // die "version is required\n";
    my @required = @{$args{required} // []};
    my @candidates;
    push @candidates, $ENV{PERLONJAVA_UNICODE_ROOT}
        if defined $ENV{PERLONJAVA_UNICODE_ROOT} && length $ENV{PERLONJAVA_UNICODE_ROOT};
    push @candidates,
        File::Spec->catdir($root, 'perl5', 'lib', 'unicore'),
        File::Spec->catdir($root, 'dev', 'unicode', $version);

    my @diagnostics;
    for my $candidate (@candidates) {
        my @missing = grep { !-f File::Spec->catfile($candidate, $_) } @required;
        return $candidate unless @missing;
        push @diagnostics, "$candidate missing " . join(', ', @missing);
    }
    die "No complete Unicode $version source tree: " . join('; ', @diagnostics) . "\n";
}

sub select_perl_root {
    my (%args) = @_;
    my $root = $args{repo_root} // die "repo_root is required\n";
    my @required = @{$args{required} // []};
    my @candidates;
    push @candidates, $ENV{PERLONJAVA_PERL_ROOT}
        if defined $ENV{PERLONJAVA_PERL_ROOT} && length $ENV{PERLONJAVA_PERL_ROOT};
    push @candidates, File::Spec->catdir($root, 'perl5');
    if (defined $args{unicode_root}) {
        push @candidates, File::Spec->rel2abs(
            File::Spec->catdir($args{unicode_root}, '..', '..'));
    }

    my %seen;
    my @diagnostics;
    for my $candidate (@candidates) {
        $candidate = File::Spec->rel2abs($candidate);
        next if $seen{$candidate}++;
        my @missing = grep {
            !-f File::Spec->catfile($candidate, split m{/})
        } @required;
        return $candidate unless @missing;
        push @diagnostics, "$candidate missing " . join(', ', @missing);
    }
    die "No complete current Perl source tree: " . join('; ', @diagnostics) . "\n";
}

sub read_raw {
    my ($path) = @_;
    open my $input, '<:raw', $path or die "Cannot read $path: $!\n";
    local $/;
    my $text = <$input>;
    close $input or die "Cannot close $path: $!\n";
    return $text;
}

sub read_pinned_source {
    my (%args) = @_;
    my $path = $args{path} // die "path is required\n";
    my $expected_hash = $args{sha256} // die "sha256 is required for $path\n";
    my $text = read_raw($path);
    my $actual_hash = sha256_hex($text);
    die "$path SHA-256 mismatch: expected $expected_hash, found $actual_hash\n"
        unless $actual_hash eq $expected_hash;
    if (my $version_pattern = $args{version_pattern}) {
        die "$path is not pinned Unicode $args{unicode_version} data\n"
            unless $text =~ $version_pattern;
    }
    return $text;
}

sub read_unicode_version {
    my (%args) = @_;
    my $path = $args{path} // die "version path is required\n";
    my $expected = $args{expected} // die "expected version is required\n";
    my $text = defined $args{sha256}
        ? read_pinned_source(path => $path, sha256 => $args{sha256})
        : read_raw($path);
    $text =~ s/\s+\z//;
    die "Expected Unicode $expected, found '$text' in $path\n" unless $text eq $expected;
    return $text;
}

sub perl_language_version {
    my (%args) = @_;
    my $root = $args{root} // die "Perl source root is required\n";
    my $path = File::Spec->catfile($root, 'patchlevel.h');
    my $text = read_raw($path);
    my ($revision) = $text =~ /^#define\s+PERL_REVISION\s+(\d+)/m;
    my ($version) = $text =~ /^#define\s+PERL_VERSION\s+(\d+)/m;
    my ($subversion) = $text =~ /^#define\s+PERL_SUBVERSION\s+(\d+)/m;
    die "Cannot derive Perl language version from $path\n"
        unless defined $revision && defined $version && defined $subversion;
    return join '.', $revision, $version, $subversion;
}

sub trim {
    my ($text) = @_;
    $text =~ s/^\s+|\s+$//g;
    return $text;
}

sub loose_name {
    my ($text) = @_;
    $text = lc $text;
    $text =~ s/[\x09-\x0d _-]+//g;
    return $text;
}

sub parse_range {
    my ($text) = @_;
    my ($first, $last) = split /\.\./, $text;
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

sub unicode_notice_lines {
    my ($text) = @_;
    return grep { /^# (?:©|Unicode and|the U\.S\.|For terms of use and license)/ }
        split /\n/, $text;
}

sub emit_java_range_triples {
    my ($ranges, %args) = @_;
    my $output = $args{output} // *STDOUT;
    my $per_line = $args{per_line} // 4;
    my $indent = $args{indent} // '        ';
    for (my $index = 0; $index < @$ranges; $index += $per_line) {
        my $end = $index + $per_line - 1 < $#$ranges
            ? $index + $per_line - 1 : $#$ranges;
        print {$output} $indent, join(', ', map {
            sprintf '0x%X, 0x%X, %d', @{$ranges->[$_]}[0, 1, 2]
        } $index .. $end), ",\n";
    }
}

sub emit_unicode_source_notices {
    my ($sources, %args) = @_;
    my $output = $args{output} // *STDOUT;
    for my $source (@$sources) {
        print {$output} " * Source: $source->{name}\n";
        for my $line (unicode_notice_lines($source->{text})) {
            $line =~ s/^# / * /;
            print {$output} "$line\n";
        }
        print {$output} " *\n";
    }
}

1;
