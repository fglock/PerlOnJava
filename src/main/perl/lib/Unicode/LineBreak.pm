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

package Unicode::GCString;

# Minimal grapheme-cluster string class.  Uses \X to split the string
# into grapheme clusters.  Only the methods used by Text::vCard et al
# are implemented: new, length, substr, as_string, columns.

use strict;
use warnings;

sub new {
    my ($class, $str) = @_;
    $str = '' unless defined $str;
    my @clusters = ($str =~ /(\X)/gs);
    return bless { str => $str, clusters => \@clusters }, $class;
}

sub length { return scalar @{ $_[0]->{clusters} }; }

sub as_string { return $_[0]->{str}; }

# String overload would be nice, but keep it explicit.
sub substr {
    my ($self, $start, $len) = @_;
    my @c = @{ $self->{clusters} };
    my $total = scalar @c;
    $start = 0 if !defined $start;
    if ($start < 0) { $start = $total + $start; }
    $start = 0     if $start < 0;
    $start = $total if $start > $total;
    my $end;
    if (!defined $len) {
        $end = $total;
    } elsif ($len < 0) {
        $end = $total + $len;
    } else {
        $end = $start + $len;
    }
    $end = $start if $end < $start;
    $end = $total if $end > $total;
    my $piece = join '', @c[$start .. $end - 1];
    return Unicode::GCString->new($piece);
}

# Approximate column width (1 per grapheme cluster).
sub columns { return scalar @{ $_[0]->{clusters} }; }

use overload
    '""'   => \&as_string,
    'bool' => sub { CORE::length( $_[0]->{str} ) > 0 },
    '0+'   => \&length,
    fallback => 1;

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
