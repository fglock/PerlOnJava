# PerlOnJava compatibility layer for the XS-backed JSON::XS distribution.
#
# Cpanel::JSON::XS is already provided by PerlOnJava on top of the bundled
# JSON::PP implementation.  JSON::XS and Cpanel::JSON::XS intentionally share
# the core object and functional APIs, so reuse that implementation instead of
# asking CPAN to build native C code that cannot run on the JVM.

package JSON::XS;

use strict;
use warnings;

require Cpanel::JSON::XS;
require Exporter;

our $VERSION    = '4.04';
our $XS_VERSION = $VERSION;
our @ISA        = qw(Cpanel::JSON::XS Exporter);
our @EXPORT     = qw(encode_json decode_json);
our @EXPORT_OK  = qw(encode_json decode_json true false is_bool);

*encode_json = \&Cpanel::JSON::XS::encode_json;
*decode_json = \&Cpanel::JSON::XS::decode_json;
*true        = \&Cpanel::JSON::XS::true;
*false       = \&Cpanel::JSON::XS::false;
*is_bool     = \&Cpanel::JSON::XS::is_bool;

1;

__END__

=head1 NAME

JSON::XS - JSON::XS-compatible API via PerlOnJava's bundled JSON backend

=head1 DESCRIPTION

This portability layer reuses the bundled Cpanel::JSON::XS/JSON::PP stack so
modules requiring JSON::XS can run without loading a native XS extension.

=cut
