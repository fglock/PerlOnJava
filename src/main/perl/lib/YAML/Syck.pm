package YAML::Syck;

use strict;
use warnings;
use YAML::PP ();
use JSON::PP ();

# Pure-Perl bootstrap for the low-level entry points normally supplied by
# YAML::Syck's libsyck XS extension.  The public CPAN module remains in charge
# of its API wrappers; XSLoader evaluates this file only when that extension
# asks for a PerlOnJava implementation.

sub LoadYAML {
    my ($yaml) = @_;
    my @documents = YAML::PP::Load($yaml);
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
