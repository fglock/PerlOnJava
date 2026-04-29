package Unicode::LineBreak;

# Minimal pure-Perl shim of Unicode::LineBreak for PerlOnJava.
#
# The original module is XS-based and provides UAX #14 line breaking
# plus the Unicode::GCString grapheme-cluster API.  PerlOnJava ships
# only the tiny subset of GCString used by modules like Text::vCard.
#
# If you need the full functionality, please open an issue.

use strict;
use warnings;

our $VERSION = '2019.001';

# Constants commonly imported from Unicode::LineBreak
use constant {
    MANDATORY  => 0,
    DIRECT     => 1,
    INDIRECT   => 2,
    PROHIBITED => 3,
};

require Exporter;
our @ISA       = qw(Exporter);
our @EXPORT_OK = qw(MANDATORY DIRECT INDIRECT PROHIBITED context);
our %EXPORT_TAGS = ( 'all' => \@EXPORT_OK );

sub new {
    my ($class, %opts) = @_;
    return bless { %opts }, $class;
}

sub context { return 'NONEASTASIAN'; }

# Minimal break(): just returns the input unchanged as a single chunk.
sub break {
    my ($self, $str) = @_;
    return defined $str ? $str : '';
}

# The Unicode::GCString package now lives in its own file
# (lib/Unicode/GCString.pm) so that:
#   * `use Unicode::GCString` works without first loading
#     Unicode::LineBreak (e.g. String::Print does this);
#   * the MakeMaker SKIP-bundled-file logic detects
#     jar:PERL5LIB/Unicode/GCString.pm and refuses to overwrite the
#     pure-Perl shim with the XS-needing version from CPAN's
#     Unicode-LineBreak distribution.
require Unicode::GCString;

1;

__END__

=head1 NAME

Unicode::LineBreak - Minimal PerlOnJava shim

=head1 DESCRIPTION

Provides just enough of L<Unicode::LineBreak> and L<Unicode::GCString>
for modules like L<Text::vCard> that only need basic grapheme cluster
splitting.  The full UAX #14 line-breaking algorithm is not
implemented.

=cut
