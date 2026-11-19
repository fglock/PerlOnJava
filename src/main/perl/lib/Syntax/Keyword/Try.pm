package Syntax::Keyword::Try;

use strict;
use warnings;
our $VERSION = '0.32';

# PerlOnJava parses this syntax through the same AST and runtime path as Perl's
# core try feature. Keeping the pragma small also lets system Perl use its own
# implementation as the behavioral oracle.
sub import {
    shift;
    for my $option (@_) {
        die "Syntax::Keyword::Try: unsupported import option $option\n"
            unless $option eq 'try' || $option eq ':experimental';
    }
    return;
}

sub unimport { return }

1;

__END__

=head1 NAME

Syntax::Keyword::Try - PerlOnJava compatibility provider for try/catch/finally

=head1 DESCRIPTION

Enables PerlOnJava's native C<try>, C<catch>, and C<finally> syntax for the
current lexical scope. The syntax is compiled to the shared try AST and works
with both execution backends. Typed catches and C<try do> expressions are not
provided. The C<:experimental> import option silences the corresponding
experimental warning. The C<no> form removes the feature from the current scope.

=cut
