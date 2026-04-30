package YAML::XS;

#
# PerlOnJava shim for YAML::XS.
#
# The real YAML::XS distribution wraps libyaml via XS. PerlOnJava cannot
# load XS, so this module re-implements YAML::XS' public API on top of
# the bundled YAML::PP (backed by SnakeYAML on the JVM).
#
# This shim is bundled with PerlOnJava so `use YAML::XS;` works out of
# the box. If jcpan installs the upstream YAML::XS.pm into
# ~/.perlonjava/lib, that copy will be picked up first, but it relies on
# YAML::XS::LibYAML which we also provide as a shim.
#
# Limitations:
#  - YAML::XS configuration globals ($Boolean, $Indent, $LoadCode,
#    $DumpCode, $UseCode, $LoadBlessed, $ForbidDuplicateKeys,
#    $QuoteNumericStrings, $coderef2text, $glob2hash) are accepted but
#    have no effect.
#  - !!perl/code, !!perl/regexp and !!perl/glob round-tripping is not
#    supported.
#

use strict;
use warnings;

our $VERSION = '0.906.0';

use base 'Exporter';
our @EXPORT    = qw(Load Dump);
our @EXPORT_OK = qw(Load Dump LoadFile DumpFile);
our %EXPORT_TAGS = ( all => [qw(Load Dump LoadFile DumpFile)] );

our (
    $Boolean,
    $DumpCode,
    $ForbidDuplicateKeys,
    $Indent,
    $LoadBlessed,
    $LoadCode,
    $UseCode,
    $QuoteNumericStrings,
    $coderef2text,
    $glob2hash,
);
$ForbidDuplicateKeys = 0;
$QuoteNumericStrings = 1;

use YAML::PP ();
use Scalar::Util qw(openhandle);

sub Load { YAML::PP::Load(@_) }
sub Dump { YAML::PP::Dump(@_) }

sub LoadFile {
    my $filename = shift;
    my $IN;
    if (openhandle($filename)) {
        $IN = $filename;
    }
    else {
        open $IN, '<', $filename
            or die "Can't open '$filename' for input:\n$!";
    }
    my $yaml = do { local $/; scalar <$IN> };
    return YAML::PP::Load($yaml);
}

sub DumpFile {
    my $filename = shift;
    my $OUT;
    if (openhandle($filename)) {
        $OUT = $filename;
    }
    else {
        my $mode = '>';
        if ($filename =~ /^\s*(>{1,2})\s*(.*)$/) {
            ($mode, $filename) = ($1, $2);
        }
        open $OUT, $mode, $filename
            or die "Can't open '$filename' for output:\n$!";
    }
    local $/ = "\n";
    print $OUT YAML::PP::Dump(@_);
}

1;
