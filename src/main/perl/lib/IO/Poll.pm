
# IO::Poll.pm
#
# Copyright (c) 1997-8 Graham Barr <gbarr@pobox.com>. All rights reserved.
# This program is free software; you can redistribute it and/or
# modify it under the same terms as Perl itself.

package IO::Poll;

use strict;
use IO::Handle;
use Exporter ();

our @ISA = qw(Exporter);
our $VERSION = "1.55";

# Load Java XS backend (_poll function)
require XSLoader;
XSLoader::load('IO::Poll', $VERSION);

# Poll constants (matching POSIX poll.h values)
use constant POLLIN     => 0x0001;
use constant POLLPRI    => 0x0002;
use constant POLLOUT    => 0x0004;
use constant POLLERR    => 0x0008;
use constant POLLHUP    => 0x0010;
use constant POLLNVAL   => 0x0020;
use constant POLLRDNORM => 0x0040;
use constant POLLWRNORM => POLLOUT;
use constant POLLRDBAND => 0x0080;
use constant POLLWRBAND => 0x0100;
use constant POLLNORM   => POLLRDNORM;

our @EXPORT = qw( POLLIN
	      POLLOUT
	      POLLERR
	      POLLHUP
	      POLLNVAL
	    );

our @EXPORT_OK = qw(
 POLLPRI
 POLLRDNORM
 POLLWRNORM
 POLLRDBAND
 POLLWRBAND
 POLLNORM
	       );

# [0] maps fd's to requested masks
# [1] maps fd's to returned  masks
# [2] maps fd's to handles
sub new {
    my $class = shift;

    my $self = bless [{},{},{}], $class;

    $self;
}

sub mask {
    my $self = shift;
    my $io = shift;
    my $fd = fileno($io);
    return unless defined $fd;
    if (@_) {
	my $mask = shift;
	if($mask) {
	  $self->[0]{$fd}{$io} = $mask; # the error events are always returned
	  $self->[1]{$fd}      = 0;     # output mask
	  $self->[2]{$io}      = $io;   # remember handle
	} else {
          delete $self->[0]{$fd}{$io};
          unless(%{$self->[0]{$fd}}) {
            # We no longer have any handles for this FD
            delete $self->[1]{$fd};
            delete $self->[0]{$fd};
          }
          delete $self->[2]{$io};
	}
    }
    
    return unless exists $self->[0]{$fd} and exists $self->[0]{$fd}{$io};
	return $self->[0]{$fd}{$io};
}


sub poll {
    my($self,$timeout) = @_;

    $self->[1] = {};

    my($fd,$mask,$iom);
    my @poll = ();

    while(($fd,$iom) = each %{$self->[0]}) {
	$mask   = 0;
	$mask  |= $_ for values(%$iom);
	push(@poll,$fd => $mask);
    }

    my $ret = _poll(defined($timeout) ? $timeout * 1000 : -1,@poll);

    return $ret
	unless $ret > 0;

    while(@poll) {
	my($fd,$got) = splice(@poll,0,2);
	$self->[1]{$fd} = $got if $got;
    }

    return $ret;  
}

sub events {
    my $self = shift;
    my $io = shift;
    my $fd = fileno($io);
    exists $self->[1]{$fd} and exists $self->[0]{$fd}{$io} 
                ? $self->[1]{$fd} & ($self->[0]{$fd}{$io}|POLLHUP|POLLERR|POLLNVAL)
	: 0;
}

sub remove {
    my $self = shift;
    my $io = shift;
    $self->mask($io,0);
}

sub handles {
    my $self = shift;
    return values %{$self->[2]} unless @_;

    my $events = shift || 0;
    my($fd,$ev,$io,$mask);
    my @handles = ();

    while(($fd,$ev) = each %{$self->[1]}) {
	while (($io,$mask) = each %{$self->[0]{$fd}}) {
	    $mask |= POLLHUP|POLLERR|POLLNVAL;  # must allow these
	    push @handles,$self->[2]{$io} if ($ev & $mask) & $events;
	}
    }
    return @handles;
}

1;

__END__

=head1 NAME

IO::Poll - Object interface to system poll call

=head1 DESCRIPTION

PerlOnJava port of IO::Poll from IO-1.55.
Java backend: src/main/java/org/perlonjava/runtime/perlmodule/IOPoll.java

=head1 AUTHOR

Graham Barr. Currently maintained by the Perl Porters.

=head1 COPYRIGHT

Copyright (c) 1997-8 Graham Barr <gbarr@pobox.com>. All rights reserved.
This program is free software; you can redistribute it and/or
modify it under the same terms as Perl itself.

=cut
