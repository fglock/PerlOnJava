package Future::AsyncAwait::Awaitable;

use v5.14;
use warnings;

our $VERSION = '0.71';

# This is the pure-Perl interface role shipped by Future::AsyncAwait.  The
# syntax/runtime implementation is independent of Role::Tiny, so loading this
# module remains harmless when Role::Tiny is unavailable.
if (defined eval { require Role::Tiny; 1 }) {
    Role::Tiny->init_role(__PACKAGE__);
    my %helpers = Role::Tiny->_gen_subs(__PACKAGE__);
    $helpers{requires}->(qw(
        AWAIT_CLONE AWAIT_NEW_DONE AWAIT_NEW_FAIL
        AWAIT_DONE AWAIT_FAIL AWAIT_GET
        AWAIT_IS_READY AWAIT_ON_READY
        AWAIT_IS_CANCELLED AWAIT_ON_CANCEL
        AWAIT_WAIT
    ));
}

1;

__END__

=head1 NAME

Future::AsyncAwait::Awaitable - interface required by Future::AsyncAwait

=head1 DESCRIPTION

Declares the Awaitable method contract as a Role::Tiny role when Role::Tiny is
available. The capitalized C<AWAIT_*> methods cover construction, readiness,
completion, failure, cancellation callbacks, result retrieval, and top-level
waiting.

=head1 COPYRIGHT AND LICENSE

Copyright 2019-2024 Paul Evans.

This library is free software; you may redistribute it and/or modify it under
the same terms as Perl itself.

=cut
