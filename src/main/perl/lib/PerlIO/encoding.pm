package PerlIO::encoding;

use strict;
use warnings;

our $VERSION = '0.30';

# Fallback flags for encoding error handling.
# Modules check this variable to decide how to handle encoding errors.
our $fallback = 0;  # Encode::FB_QUIET equivalent

# In standard Perl, PerlIO::encoding is an XS module that provides
# the :encoding() PerlIO layer. In PerlOnJava, IO layers are handled
# by the Java LayeredIOHandle implementation. This stub provides the
# package variables that other modules (IO::HTML, etc.) expect.

1;
__END__

=head1 NAME

PerlIO::encoding - encoding layer stub for PerlOnJava

=head1 DESCRIPTION

Stub module providing the C<$PerlIO::encoding::fallback> variable.
The actual encoding layer functionality is implemented in the Java backend.

=cut
