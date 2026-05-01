package Text::Fuzzy;

# PerlOnJava pure-Perl implementation of Text::Fuzzy.
#
# The upstream CPAN distribution (by Ben Kasmin Bullock) is an XS module that
# computes Levenshtein edit distances in C for speed.  PerlOnJava cannot load
# native XS extensions, so this shim re-implements the public API used by
# CPAN::Nearest (and compatible callers) in pure Perl.
#
# Implemented API:
#   Text::Fuzzy->new($needle, %opts)   constructor
#   $tf->distance($string)             edit distance from needle to string
#   $tf->nearest(\@array)              index of closest array element (or undef)
#   $tf->nearestv(\@array)             value  of closest array element (or undef)
#   $tf->get_max_distance()            current max threshold
#   $tf->set_max_distance($n)          update max threshold
#
# Options accepted by new():
#   max          => $n   - maximum edit distance (returns undef if none ≤ max)
#   max_distance => $n   - alias for max
#   no_exact     => 1    - do not return exact (distance == 0) match

use strict;
use warnings;

require Exporter;
our @ISA       = qw(Exporter);
our @EXPORT_OK = qw(fuzzy_index distance_edits);

our $VERSION = '0.29';

# ---------------------------------------------------------------------------
# Constructor
# ---------------------------------------------------------------------------
sub new {
    my ($class, $needle, %opts) = @_;
    my $max = defined $opts{max}          ? $opts{max}
            : defined $opts{max_distance} ? $opts{max_distance}
            : undef;
    return bless {
        needle     => $needle,
        max        => $max,
        no_exact   => $opts{no_exact} || 0,
    }, $class;
}

# ---------------------------------------------------------------------------
# Accessors for max distance
# ---------------------------------------------------------------------------
sub get_max_distance { $_[0]->{max} }
sub set_max_distance { $_[0]->{max} = $_[1] }

# ---------------------------------------------------------------------------
# Levenshtein distance with optional early-exit when exceeding $max.
# Returns the edit distance, or ($max + 1) if it would exceed $max.
# ---------------------------------------------------------------------------
sub _edit_distance {
    my ($s, $t, $max) = @_;

    # Treat undef as empty string
    $s = '' unless defined $s;
    $t = '' unless defined $t;

    my $n = length($s);
    my $m = length($t);

    # Trivial cases
    return $m if $n == 0;
    return $n if $m == 0;

    # Fast-path: if lengths differ by more than max we know it's too far
    if (defined $max && abs($n - $m) > $max) {
        return $max + 1;
    }

    # Standard DP over rows; keep only two rows to save memory.
    my @prev = (0 .. $m);   # prev[j] = edit distance for s[0..i-1], t[0..j-1]
    my @curr;

    for my $i (1 .. $n) {
        $curr[0] = $i;
        my $row_min = $i;   # Track the minimum in this row for early exit

        for my $j (1 .. $m) {
            my $cost = (substr($s, $i-1, 1) eq substr($t, $j-1, 1)) ? 0 : 1;
            my $del  = $prev[$j]   + 1;
            my $ins  = $curr[$j-1] + 1;
            my $sub  = $prev[$j-1] + $cost;
            my $val  = $del < $ins  ? $del  : $ins;
               $val  = $sub         if $sub  < $val;
            $curr[$j] = $val;
            $row_min  = $val if $val < $row_min;
        }

        # Early exit: if the minimum value in this row already exceeds max,
        # no path can achieve <= max.
        if (defined $max && $row_min > $max) {
            return $max + 1;
        }

        @prev = @curr;
    }

    return $prev[$m];
}

# ---------------------------------------------------------------------------
# Public instance method: distance from needle to a single string
# ---------------------------------------------------------------------------
sub distance {
    my ($self, $str) = @_;
    return _edit_distance($self->{needle}, $str, $self->{max});
}

# ---------------------------------------------------------------------------
# Public instance method: index of nearest element in array ref, or undef
# ---------------------------------------------------------------------------
sub nearest {
    my ($self, $array_ref) = @_;
    return undef unless defined $array_ref && @$array_ref;

    my $needle    = $self->{needle};
    my $max       = $self->{max};
    my $no_exact  = $self->{no_exact};
    my $best_dist = defined $max ? $max : 1_000_000_000;
    my $best_idx  = undef;

    for my $i (0 .. $#$array_ref) {
        my $d = _edit_distance($needle, $array_ref->[$i], $best_dist);
        next if $no_exact && $d == 0;
        if ($d < $best_dist) {
            $best_dist = $d;
            $best_idx  = $i;
        }
    }

    return $best_idx;
}

# ---------------------------------------------------------------------------
# Public instance method: nearest value (string), or undef
# ---------------------------------------------------------------------------
sub nearestv {
    my ($self, $array_ref) = @_;
    if (wantarray) {
        my @offsets = $self->nearest_all($array_ref);
        return map { $array_ref->[$_] } @offsets;
    }
    else {
        my $idx = $self->nearest($array_ref);
        return defined $idx ? $array_ref->[$idx] : undef;
    }
}

# ---------------------------------------------------------------------------
# Return ALL indices tied for nearest (not used by CPAN::Nearest but part of
# the documented API).
# ---------------------------------------------------------------------------
sub nearest_all {
    my ($self, $array_ref) = @_;
    return () unless defined $array_ref && @$array_ref;

    my $needle   = $self->{needle};
    my $max      = $self->{max};
    my $no_exact = $self->{no_exact};
    my $best_dist = defined $max ? $max : 1_000_000_000;
    my @best_idx;

    for my $i (0 .. $#$array_ref) {
        my $d = _edit_distance($needle, $array_ref->[$i], $best_dist);
        next if $no_exact && $d == 0;
        if ($d < $best_dist) {
            $best_dist = $d;
            @best_idx  = ($i);
        }
        elsif ($d == $best_dist) {
            push @best_idx, $i;
        }
    }

    return @best_idx;
}

# ---------------------------------------------------------------------------
# Exportable procedural helpers (from the original XS module)
# ---------------------------------------------------------------------------

sub distance_edits {
    return fuzzy_index(@_, 1);
}

# fuzzy_index($needle, $haystack, $want_edits)
# Returns ($best_match_end_pos, $edit_path, $distance) or similar.
# Used internally; exposed for compatibility.
sub fuzzy_index {
    my ($needle, $haystack, $distance_flag) = @_;
    # Minimal compatible implementation: just return edit distance.
    my $d = _edit_distance($needle, $haystack, undef);
    return ($d) unless $distance_flag;
    return ($d, '', $d);
}

1;

__END__

=head1 NAME

Text::Fuzzy - PerlOnJava pure-Perl shim for Text::Fuzzy

=head1 DESCRIPTION

This is a pure-Perl re-implementation of the L<Text::Fuzzy> XS module for
use inside PerlOnJava, which cannot load native XS extensions.  The
Levenshtein edit-distance algorithm includes early-exit optimisation when
a C<max> threshold is set, so performance on large arrays is acceptable.

=head1 VERSION

This shim reports version 0.29, matching the CPAN distribution version at
the time of writing.

=head1 SEE ALSO

L<Text::Fuzzy> on CPAN (XS original by Ben Kasmin Bullock).

=cut
