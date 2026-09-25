package YAML::Syck;

use strict;
use warnings;
use Exporter 'import';
use Scalar::Util qw(openhandle);
use YAML::PP ();
use JSON::PP ();

our @EXPORT    = qw(Dump Load DumpFile LoadFile);
our @EXPORT_OK = qw(Dump Load DumpFile LoadFile DumpInto LoadBytes LoadUTF8 DumpBytes DumpUTF8);

# Pure-Perl bootstrap for the low-level entry points normally supplied by
# YAML::Syck's libsyck XS extension.  The public CPAN module remains in charge
# of its API wrappers; XSLoader evaluates this file only when that extension
# asks for a PerlOnJava implementation.

sub Dump {
    return join('', map { DumpYAML($_) } @_) if @_ > 1;
    return DumpYAML($_[0]);
}

sub Load {
    if (wantarray) {
        my ($documents) = LoadYAML($_[0]);
        return ref($documents) eq 'ARRAY' ? @{$documents} : ();
    }
    return LoadYAML($_[0]);
}

sub _is_filehandle {
    my ($file) = @_;
    return openhandle($file) || ref($file) eq 'GLOB';
}

sub DumpFile {
    my $file = shift;
    my $fh;
    my $is_fh = _is_filehandle($file);
    if ($is_fh) {
        $fh = $file;
    } else {
        open($fh, '>', $file) or die "Cannot write to $file: $!";
    }
    print {$fh} Dump(@_) or die "Error writing to $file: $!";
    close($fh) unless $is_fh;
    return 1;
}

sub LoadFile {
    my ($file) = @_;
    my $fh;
    my $is_fh = _is_filehandle($file);
    if ($is_fh) {
        $fh = $file;
    } else {
        open($fh, '<', $file) or die "Cannot read from $file: $!";
    }
    local $/;
    my $yaml = <$fh>;
    close($fh) unless $is_fh;
    return Load($yaml);
}

sub DumpInto {
    my $buffer = shift;
    die "DumpInto not given reference to output buffer\n" unless ref($buffer);
    $$buffer .= Dump(@_);
    return 1;
}

sub LoadBytes { return Load($_[0]); }
sub LoadUTF8  { return Load($_[0]); }
sub DumpBytes { return Dump(@_); }
sub DumpUTF8  { return Dump(@_); }

sub _normalize_syck_percent_scalars {
    my ($yaml) = @_;

    # YAML::Syck accepts percent-leading plain mapping keys and values that
    # SnakeYAML (used by YAML::PP) correctly rejects as YAML directives.  The
    # old module is commonly used for configuration templates containing
    # placeholders such as %TOP% and %DATADIR%; quote only those scalar forms
    # before handing the document to YAML::PP.
    $yaml =~ s{^(\s*)(%[^:\n]+):(.*)$}{$1'$2':$3}mg;
    $yaml =~ s{^(\s*[^#:\n]+:\s+)(%[^#\n]+?)(\s*(?:#.*)?)$}{$1'$2'$3}mg;
    $yaml =~ s{^(\s*-\s+)(%[^#\n]+?)(\s*(?:#.*)?)$}{$1'$2'$3}mg;
    $yaml =~ s{^([ \t]*[^:\n]+:)[ \t]*\t+[ \t]*}{$1 }mg;
    return $yaml;
}

sub LoadYAML {
    my ($yaml) = @_;
    $yaml = _normalize_syck_percent_scalars($yaml);
    my $yaml_pp = YAML::PP->new(duplicate_keys => 1);
    my @documents = $yaml_pp->load_string($yaml);
    return wantarray ? \@documents : $documents[0];
}

sub DumpYAML {
    return YAML::PP::Dump($_[0]);
}

sub LoadJSON {
    return JSON::PP::decode_json($_[0]);
}

sub DumpJSON {
    return JSON::PP->new->allow_nonref->encode($_[0]);
}

sub DumpYAMLFile {
    my ($value, $fh) = @_;
    return print {$fh} DumpYAML($value) ? 0 : 0 + $!;
}

sub DumpJSONFile {
    my ($value, $fh) = @_;
    return print {$fh} DumpJSON($value) ? 0 : 0 + $!;
}

sub DumpYAMLInto {
    my ($value, $buffer) = @_;
    $$buffer .= DumpYAML($value);
    return 1;
}

sub DumpJSONInto {
    my ($value, $buffer) = @_;
    $$buffer .= DumpJSON($value);
    return 1;
}

1;
