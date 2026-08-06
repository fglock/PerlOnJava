package Unicode::GCString;

# Minimal pure-Perl shim of Unicode::GCString for PerlOnJava.
#
# The original module is part of the XS-based Unicode::LineBreak
# distribution and provides a grapheme-cluster string API.  PerlOnJava
# ships only the tiny subset of GCString that downstream modules
# (String::Print, Text::vCard, ...) actually use.
#
# If a CPAN install of Unicode::LineBreak would otherwise overwrite
# this file with the XS-needing version, MakeMaker.pm in PerlOnJava
# detects the bundled copy in jar:PERL5LIB/Unicode/GCString.pm and
# skips it, preserving this shim.
#
# If you need the full functionality, please open an issue.

use strict;
use warnings;

our $VERSION = '2019.001';

sub new {
    my ($class, $str) = @_;
    $str = '' unless defined $str;
    my @clusters = ($str =~ /(\X)/gs);
    return bless { str => $str, clusters => \@clusters }, $class;
}

sub length { return scalar @{ $_[0]->{clusters} }; }

sub as_string { return $_[0]->{str}; }

sub substr {
    my ($self, $start, $len) = @_;
    my @c = @{ $self->{clusters} };
    my $total = scalar @c;
    $start = 0 if !defined $start;
    if ($start < 0) { $start = $total + $start; }
    $start = 0      if $start < 0;
    $start = $total if $start > $total;
    my $end;
    if (!defined $len) {
        $end = $total;
    } elsif ($len < 0) {
        $end = $total + $len;
    } else {
        $end = $start + $len;
    }
    $end = $start  if $end < $start;
    $end = $total  if $end > $total;
    my $piece = join '', @c[$start .. $end - 1];
    return Unicode::GCString->new($piece);
}

# Terminal column width. East Asian Wide and Fullwidth graphemes occupy two
# columns; combining marks remain part of their base grapheme cluster.
sub columns {
    my $columns = 0;
    for my $cluster (@{ $_[0]->{clusters} }) {
        $columns += $cluster =~ /\p{East_Asian_Width=Wide}/
                || $cluster =~ /\p{East_Asian_Width=Fullwidth}/ ? 2 : 1;
    }
    return $columns;
}

use overload
    '""'     => \&as_string,
    'bool'   => sub { CORE::length( $_[0]->{str} ) > 0 },
    '0+'     => \&length,
    fallback => 1;

1;

__END__

=head1 NAME

Unicode::GCString - Minimal PerlOnJava shim

=head1 DESCRIPTION

Provides just enough of L<Unicode::GCString> for modules like
L<String::Print> and L<Text::vCard> that only need basic grapheme
cluster splitting.

=cut
