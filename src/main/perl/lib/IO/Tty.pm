# Documentation at the __END__

package IO::Tty;

use strict;
use warnings;
use IO::Handle;
use IO::File;
use IO::Tty::Constant;
use Carp;

require POSIX;

our @ISA        = qw(IO::Handle);
our $VERSION = '1.27';
our ( $CONFIG, $DEBUG );

eval { local $^W = 0; local $SIG{__DIE__}; require IO::Stty };
push @ISA, "IO::Stty" if ( not $@ );    # if IO::Stty is installed

use XSLoader;
XSLoader::load(__PACKAGE__, $VERSION);

sub import {
    IO::Tty::Constant->export_to_level( 1, @_ );
}

sub open {
    my ( $tty, $dev, $mode ) = @_;

    IO::File::open( $tty, $dev, $mode )
      or return undef;

    $tty->autoflush;

    1;
}

sub clone_winsize_from {
    my ( $self, $fh ) = @_;
    croak "Given filehandle is not a tty in clone_winsize_from, called"
      if not POSIX::isatty($fh);
    return 1 if not POSIX::isatty($self);    # ignored for master ptys
    my $winsize = "\0" x 8;                  # struct winsize is 8 bytes
    ioctl( $fh, &IO::Tty::Constant::TIOCGWINSZ, $winsize )
      and ioctl( $self, &IO::Tty::Constant::TIOCSWINSZ, $winsize )
      and return 1;
    warn "clone_winsize_from: error: $!" if $^W;
    return undef;
}

# ioctl() doesn't tell us how long the structure is, so we'll have to trim it
# after TIOCGWINSZ
my $SIZEOF_WINSIZE = length IO::Tty::pack_winsize( 0, 0, 0, 0 );

sub get_winsize {
    my $self = shift;
    my $winsize = " " x 1024;    # preallocate memory
    ioctl( $self, IO::Tty::Constant::TIOCGWINSZ(), $winsize )
      or croak "Cannot TIOCGWINSZ - $!";
    substr( $winsize, $SIZEOF_WINSIZE ) = "";
    return IO::Tty::unpack_winsize($winsize);
}

sub set_winsize {
    my $self    = shift;
    my $winsize = IO::Tty::pack_winsize(@_);
    ioctl( $self, IO::Tty::Constant::TIOCSWINSZ(), $winsize )
      or croak "Cannot TIOCSWINSZ - $!";
}

sub set_raw($) {
    require POSIX;
    my $self = shift;
    return 1 if not POSIX::isatty($self);
    my $ttyno   = fileno($self);
    my $termios = POSIX::Termios->new;
    unless ($termios) {
        warn "set_raw: new POSIX::Termios failed: $!";
        return undef;
    }
    unless ( $termios->getattr($ttyno) ) {
        warn "set_raw: getattr($ttyno) failed: $!";
        return undef;
    }
    $termios->setiflag(0);
    $termios->setoflag(0);
    $termios->setlflag(0);
    $termios->setcflag(
        ( $termios->getcflag() & ~( &POSIX::CSIZE | &POSIX::PARENB ) )
        | &POSIX::CS8
    );
    $termios->setcc( &POSIX::VMIN,  1 );
    $termios->setcc( &POSIX::VTIME, 0 );
    unless ( $termios->setattr( $ttyno, &POSIX::TCSANOW ) ) {
        warn "set_raw: setattr($ttyno) failed: $!";
        return undef;
    }
    return 1;
}

1;

__END__

=head1 NAME

IO::Tty - Low-level allocate a pseudo-Tty, import constants.

=head1 VERSION

1.27

=head1 DESCRIPTION

PerlOnJava port of IO::Tty. See L<IO::Pty> for creating ptys.

=head1 AUTHORS

Originally by Graham Barr, based on the Ptty module by Nick Ing-Simmons.
Maintained by Roland Giersig. Ported to PerlOnJava.

=head1 COPYRIGHT

Free software; you can redistribute it and/or modify it under the same
terms as Perl itself.

=cut
