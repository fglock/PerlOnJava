package B::Hooks::EndOfScope;
use strict;
use warnings;

our $VERSION = '0.26';

use Exporter 'import';
our @EXPORT = qw(on_scope_end);
our @EXPORT_OK = qw(on_scope_end);

# Load the Java XS implementation which provides on_scope_end()
# This uses PerlOnJava's defer mechanism (DeferBlock + DynamicVariableManager)
use XSLoader;
XSLoader::load('B::Hooks::EndOfScope', $VERSION);

1;

__END__

=head1 NAME

B::Hooks::EndOfScope - Execute code after a scope finished compilation

=head1 SYNOPSIS

    use B::Hooks::EndOfScope;

    on_scope_end { print "scope ended\n" };

=head1 DESCRIPTION

This module provides the C<on_scope_end> function which registers a callback
to be executed when the current scope exits.

=head2 PerlOnJava Implementation

In PerlOnJava, this is implemented using the defer mechanism. When you call
C<on_scope_end>, the callback is registered as a defer block that will execute
when the enclosing scope exits (via normal flow, return, die, etc.).

This differs slightly from the original Perl implementation which uses compile-time
hooks, but the end result is the same: your callback runs at scope exit.

=head1 FUNCTIONS

=head2 on_scope_end

    on_scope_end { ... };
    on_scope_end(sub { ... });

Registers a callback to be executed when the current scope exits.
Multiple callbacks are executed in LIFO (last-in, first-out) order.

=head1 SEE ALSO

L<namespace::autoclean>, L<namespace::clean>

=cut
