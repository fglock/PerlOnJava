package PerlIO::utf8_strict;

use strict;
use warnings;

our $VERSION = '0.010';

# In standard Perl, PerlIO::utf8_strict is an XS module that provides
# a :utf8_strict PerlIO layer (a stricter UTF-8 layer that rejects
# malformed sequences). In PerlOnJava, IO layers are handled by the
# Java LayeredIOHandle implementation, which treats :utf8_strict as
# an alias for :utf8 (the JVM's UTF-8 decoder already rejects malformed
# input by default via CharsetDecoder, so the "strict" semantics are
# effectively the default).
#
# This stub lets `use PerlIO::utf8_strict;` succeed so CPAN modules
# whose prerequisite chain lists PerlIO::utf8_strict (e.g.
# Mixin::Linewise -> Config::INI -> Config::INI::Reader::Ordered ->
# Reply) can be loaded.

1;
__END__

=head1 NAME

PerlIO::utf8_strict - stub module for PerlOnJava

=head1 DESCRIPTION

Stub for PerlIO::utf8_strict. The C<:utf8_strict> layer is treated
as an alias for C<:utf8> by PerlOnJava's LayeredIOHandle.

=cut
