package Syntax::Feature::Method;

use strict;
use warnings;

our $VERSION = '0.01';

sub import { return }
sub unimport { return }

1;

__END__

=head1 NAME

Syntax::Feature::Method - PerlOnJava method declaration parser provider

=head1 DESCRIPTION

Enables the supported C<method> declaration syntax through PerlOnJava's
Function::Parameters-compatible parser and shared subroutine representation.
The general Devel::Declare and B::Hooks callback APIs are not implemented.

=cut
