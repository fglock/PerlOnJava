package Clone::PP;

use strict;
use warnings;

our $VERSION = '1.09';

use Storable ();
use Exporter 'import';
our @EXPORT_OK = qw(clone);

sub clone {
    return Storable::dclone($_[0]);
}

1;
__END__

=head1 NAME

Clone::PP - Recursively copy Perl datatypes (pure Perl)

=head1 SYNOPSIS

    use Clone::PP 'clone';
    my $copy = clone($data);

=head1 DESCRIPTION

Pure Perl implementation of Clone using Storable::dclone.
This is the PerlOnJava fallback for the Clone XS module.

=cut
