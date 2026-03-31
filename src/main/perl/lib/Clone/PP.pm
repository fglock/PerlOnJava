package Clone::PP;

use strict;
use warnings;

our $VERSION = '1.09';

use Scalar::Util qw(reftype blessed refaddr);
use Exporter 'import';
our @EXPORT_OK = qw(clone);

sub clone {
    return _clone_data($_[0], {});
}

sub _clone_data {
    my ($data, $seen) = @_;

    return $data unless ref($data);

    # Handle circular references
    my $addr = refaddr($data);
    return $seen->{$addr} if exists $seen->{$addr};

    my $rtype = reftype($data);
    my $class = blessed($data);

    if ($rtype eq 'HASH') {
        my $clone = {};
        $seen->{$addr} = $clone;
        for my $key (keys %$data) {
            $clone->{$key} = _clone_data($data->{$key}, $seen);
        }
        bless $clone, $class if defined $class;
        return $clone;
    }

    if ($rtype eq 'ARRAY') {
        my $clone = [];
        $seen->{$addr} = $clone;
        for my $item (@$data) {
            push @$clone, _clone_data($item, $seen);
        }
        bless $clone, $class if defined $class;
        return $clone;
    }

    if ($rtype eq 'SCALAR' || $rtype eq 'REF') {
        my $clone = \(my $copy = $$data);
        $seen->{$addr} = $clone;
        bless $clone, $class if defined $class;
        return $clone;
    }

    # CODE, GLOB, IO, Regexp - return as-is (immutable or not deep-cloneable)
    return $data;
}

1;
__END__

=head1 NAME

Clone::PP - Recursively copy Perl datatypes (pure Perl)

=head1 SYNOPSIS

    use Clone::PP 'clone';
    my $copy = clone($data);

=head1 DESCRIPTION

Pure Perl deep clone implementation. Handles hashes, arrays, scalar refs,
and circular references. Code refs, globs, and regexps are returned as-is
(shared, not copied) since they are immutable or not safely cloneable.

=cut
