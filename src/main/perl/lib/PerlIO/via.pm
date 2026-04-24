package PerlIO::via;

use strict;
use warnings;

our $VERSION = '0.19';

# In standard Perl, PerlIO::via is an XS module that lets you implement
# PerlIO layers in pure Perl via callbacks (PUSHED, FILL, READ, WRITE,
# CLOSE, ...). In PerlOnJava, layered I/O is dispatched in Java
# (LayeredIOHandle), and there is currently no bridge from a :via(Foo)
# layer back into user-supplied Perl callbacks.
#
# This stub exists so that:
#   * `use PerlIO::via;` loads successfully.
#   * CPAN modules whose prerequisite chain lists PerlIO::via (e.g.
#     PerlIO::via::Timeout -> IO::Socket::Timeout -> Redis) can be
#     installed and loaded.
#
# Actually opening a handle with `:via(Foo)` is a separate concern: the
# Java-side layer parser throws a clear error when it sees `:via(...)`,
# so the lack of dispatch does not fail silently. See
# dev/modules/perlio_via.md for the plan to make this functional.

1;
__END__

=head1 NAME

PerlIO::via - stub module for PerlOnJava

=head1 DESCRIPTION

Loading-only stub. The real XS implementation of the C<:via(...)>
PerlIO layer is not yet bridged to PerlOnJava's Java-side layered
I/O. An C<open> that includes C<:via(Foo)> will raise an explicit
error from the layer parser rather than silently ignoring the layer.

See C<dev/modules/perlio_via.md> for the plan to make this module
functional.

=cut
