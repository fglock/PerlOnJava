package Future::AsyncAwait;

use v5.14;
use warnings;

our $VERSION = '0.71';

# PerlOnJava implements the syntax in its own frontend. Keep activation in
# %^H so it follows Perl's normal lexical pragma scoping rules.
sub import {
    $^H |= 0x020000;
    $^H{'Future::AsyncAwait/async'} = 1;
}

sub unimport {
    delete $^H{'Future::AsyncAwait/async'};
}

1;

__END__

=head1 NAME

Future::AsyncAwait - PerlOnJava frontend compatibility facade

=head1 DESCRIPTION

This facade activates PerlOnJava's native parsing of C<async sub> and
C<await>. Runtime suspension and resumption are under development; attempting
to compile async execution currently produces an explicit unsupported-feature
diagnostic.

The public syntax and behavior are based on Paul Evans' Future::AsyncAwait.
PerlOnJava does not load or emulate its Perl-internal XS optree hooks.

=head1 AUTHOR

Paul Evans E<lt>leonerd@leonerd.org.ukE<gt>, original Future::AsyncAwait.

PerlOnJava compatibility facade maintained by the PerlOnJava contributors.

=head1 COPYRIGHT AND LICENSE

Copyright 2016-2024 Paul Evans.

This library is free software; you may redistribute it and/or modify it under
the same terms as Perl itself.

=cut
