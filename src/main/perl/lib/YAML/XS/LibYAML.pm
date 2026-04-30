package YAML::XS::LibYAML;

#
# PerlOnJava shim for YAML::XS::LibYAML.
#
# The CPAN distribution YAML::XS (a.k.a. YAML-LibYAML) ships an XS module
# called YAML::XS::LibYAML which wraps the C libyaml library. PerlOnJava
# cannot load XS, so we provide this pure-Perl stub that exports Load/Dump
# by delegating to the bundled YAML::PP implementation (backed by
# SnakeYAML on the JVM). This lets `use YAML::XS;` work for the common
# Load/Dump round-trip use cases.
#
# Limitations:
#  - YAML::XS-specific globals such as $YAML::XS::Boolean, $LoadCode,
#    $DumpCode, $UseCode, $LoadBlessed, $ForbidDuplicateKeys, $Indent
#    are accepted (the upstream YAML::XS.pm declares them) but are not
#    honoured here.
#  - Code, regexp and glob round-tripping (the !!perl/code, !!perl/regexp,
#    !!perl/glob tags) is not supported.
#

use strict;
use warnings;

our $VERSION = '0.906.0';

use base 'Exporter';
our @EXPORT_OK = qw(Load Dump);

use YAML::PP ();

sub Load {
    return YAML::PP::Load(@_);
}

sub Dump {
    return YAML::PP::Dump(@_);
}

1;
