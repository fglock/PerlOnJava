package Clone;

use strict;
use warnings;

require Exporter;

our @ISA       = qw(Exporter);
our @EXPORT;
our @EXPORT_OK = qw( clone );

our $VERSION = '0.49';

# PerlOnJava: Fall back to Clone::PP since we can't load XS.
# Note: XSLoader::load may return success via @ISA fallback without
# actually providing clone(), so we also check defined(&clone).
my $loaded = 0;

eval {
    require XSLoader;
    XSLoader::load('Clone', $VERSION);
    $loaded = 1 if defined(&clone);
};

if (!$loaded) {
    # Fall back to pure Perl implementation
    require Clone::PP;
    *clone = \&Clone::PP::clone;
}

1;
__END__

=head1 NAME

Clone - recursively copy Perl datatypes

=head1 SYNOPSIS

    use Clone 'clone';

    my $data = {
       set => [ 1 .. 50 ],
       foo => {
           answer => 42,
           object => SomeObject->new,
       },
    };

    my $cloned_data = clone($data);

=head1 DESCRIPTION

This module provides a C<clone()> method which makes recursive
copies of nested hash, array, scalar and reference types,
including tied variables and objects.

PerlOnJava uses Clone::PP (pure Perl implementation) as the backend.

=head1 SEE ALSO

L<Clone::PP>, L<Storable>

=cut
