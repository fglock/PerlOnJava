package encoding;

use strict;
use warnings;

our $VERSION = '3.00';

# PerlOnJava implementation of the deprecated `encoding` pragma.
#
# In real Perl, `use encoding 'utf8';` historically did three things:
#   1. Set the *source* encoding for the rest of the file, so byte
#      literals were decoded as the named encoding.
#   2. Optionally pushed I/O layers onto STDIN/STDOUT/STDERR (and on
#      named filehandles passed as arguments).
#   3. Made chr/ord/length operate on characters under the supplied
#      encoding (this part was always considered a misfeature).
#
# The pragma was deprecated in Perl 5.18 and removed from core in
# Perl 5.26+, but a great deal of older CPAN code (especially
# CJK-related modules such as Lingua::ZH::MMSEG) still loads it.
#
# PerlOnJava's parser already reads sources as UTF-8 unconditionally,
# so (1) is a no-op for us. (2) is best handled with explicit binmode
# / `use open ':std', ':utf8';`. (3) is intentionally not emulated.
#
# This stub therefore accepts the historical import forms and only
# applies binmode for filehandles named explicitly in the import
# list, which is what almost every surviving consumer expects:
#
#     use encoding;                                  # no-op
#     use encoding 'utf8';                           # no-op
#     use encoding 'utf8', STDIN  => 'utf8',         # binmode STDIN, STDOUT
#                          STDOUT => 'utf8';

sub import {
    my ($class, $name, %args) = @_;
    return unless defined $name;

    # Best-effort: apply layers to listed filehandles. Anything we
    # don't understand is silently ignored, matching older code's
    # tolerance.
    for my $fh_name (keys %args) {
        my $layer = $args{$fh_name};
        next unless defined $layer;

        my $glob = do { no strict 'refs'; \*{"main::$fh_name"} };
        eval { binmode $glob, ":encoding($layer)" };
    }
    return;
}

sub unimport { return }

# Historical accessors. Some old code calls encoding::name() to read
# back the configured encoding. We always report "utf8" because that
# is what PerlOnJava actually feeds the parser.
sub name { return 'utf8' }

1;

__END__

=head1 NAME

encoding - PerlOnJava no-op stub for the deprecated source-encoding pragma

=head1 SYNOPSIS

    use encoding 'utf8';

    # or with filehandle layers (these *are* applied):
    use encoding 'utf8', STDIN => 'utf8', STDOUT => 'utf8';

=head1 DESCRIPTION

The original C<encoding> pragma was deprecated in Perl 5.18 and
removed from core in Perl 5.26+. PerlOnJava parses source files as
UTF-8 unconditionally, so the source-encoding effects of the pragma
are unnecessary; this module exists purely so that older CPAN modules
that still write C<use encoding 'utf8';> can load.

When invoked with C<< STDIN | STDOUT | STDERR | $other_fh => $layer >>
arguments, this stub does call C<binmode> on the named filehandles
with C<:encoding($layer)>, matching the historical pragma's
filehandle-layer behaviour. Failures from C<binmode> are swallowed.

The C<chr>/C<ord>/C<length> overrides from the original pragma are
intentionally B<not> emulated. Use C<utf8> and explicit C<binmode>
in modern code.

=head1 SEE ALSO

L<perluniintro>, L<open>, L<binmode>.

=cut
