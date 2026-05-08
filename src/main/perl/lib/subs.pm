package subs;

use strict;
use warnings;

our $VERSION = '1.04';

sub import {
    my $callpack = caller;
    shift;

    for my $sym (@_) {
        no strict 'refs';
        *{"${callpack}::$sym"} = \&{"${callpack}::$sym"};
    }
}

1;

__END__

=head1 NAME

subs - Perl pragma to predeclare subroutine names

=head1 SYNOPSIS

    use subs qw(foo bar);

=head1 DESCRIPTION

Predeclares package subroutine names by creating CODE slots in the caller's
stash.  This matches the core pragma behavior used by modules that want to
install generated methods only when a custom method has not already been
declared.

=cut
