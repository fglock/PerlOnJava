# -*- buffer-read-only: t -*-
#
# Platform-aware errno constants for PerlOnJava.
# Based on ext/Errno/Errno_pm.PL but with runtime OS detection
# to provide correct values on both Linux and macOS/Darwin.
#

package Errno;
use Exporter 'import';
use strict;

our $VERSION = "1.37";
$VERSION = eval $VERSION;

my %err;

BEGIN {
    if ($^O eq 'darwin') {
        # macOS / Darwin (BSD) errno values from <sys/errno.h>
        %err = (
            EPERM => 1,
            ENOENT => 2,
            ESRCH => 3,
            EINTR => 4,
            EIO => 5,
            ENXIO => 6,
            E2BIG => 7,
            ENOEXEC => 8,
            EBADF => 9,
            ECHILD => 10,
            EDEADLK => 11,
            ENOMEM => 12,
            EACCES => 13,
            EFAULT => 14,
            ENOTBLK => 15,
            EBUSY => 16,
            EEXIST => 17,
            EXDEV => 18,
            ENODEV => 19,
            ENOTDIR => 20,
            EISDIR => 21,
            EINVAL => 22,
            ENFILE => 23,
            EMFILE => 24,
            ENOTTY => 25,
            ETXTBSY => 26,
            EFBIG => 27,
            ENOSPC => 28,
            ESPIPE => 29,
            EROFS => 30,
            EMLINK => 31,
            EPIPE => 32,
            EDOM => 33,
            ERANGE => 34,
            EAGAIN => 35,
            EWOULDBLOCK => 35,
            EINPROGRESS => 36,
            EALREADY => 37,
            ENOTSOCK => 38,
            EDESTADDRREQ => 39,
            EMSGSIZE => 40,
            EPROTOTYPE => 41,
            ENOPROTOOPT => 42,
            EPROTONOSUPPORT => 43,
            ESOCKTNOSUPPORT => 44,
            ENOTSUP => 45,
            EOPNOTSUPP => 45,
            EPFNOSUPPORT => 46,
            EAFNOSUPPORT => 47,
            EADDRINUSE => 48,
            EADDRNOTAVAIL => 49,
            ENETDOWN => 50,
            ENETUNREACH => 51,
            ENETRESET => 52,
            ECONNABORTED => 53,
            ECONNRESET => 54,
            ENOBUFS => 55,
            EISCONN => 56,
            ENOTCONN => 57,
            ESHUTDOWN => 58,
            ETOOMANYREFS => 59,
            ETIMEDOUT => 60,
            ECONNREFUSED => 61,
            ELOOP => 62,
            ENAMETOOLONG => 63,
            EHOSTDOWN => 64,
            EHOSTUNREACH => 65,
            ENOTEMPTY => 66,
            EUSERS => 68,
            EDQUOT => 69,
            ESTALE => 70,
            EREMOTE => 71,
            ENOLCK => 77,
            ENOSYS => 78,
            EOVERFLOW => 84,
            ECANCELED => 89,
            EIDRM => 90,
            ENOMSG => 91,
            EILSEQ => 92,
            EBADMSG => 94,
            EMULTIHOP => 95,
            ENODATA => 96,
            ENOLINK => 97,
            ENOSR => 98,
            ENOSTR => 99,
            EPROTO => 100,
            ETIME => 101,
            EOWNERDEAD => 105,
            ENOTRECOVERABLE => 104,
        );
    } elsif ($^O eq 'MSWin32') {
        # Windows (MSVC UCRT) errno values
        %err = (
            EPERM => 1,
            ENOENT => 2,
            ESRCH => 3,
            EINTR => 4,
            EIO => 5,
            ENXIO => 6,
            E2BIG => 7,
            ENOEXEC => 8,
            EBADF => 9,
            ECHILD => 10,
            EAGAIN => 11,
            EWOULDBLOCK => 138,
            ENOMEM => 12,
            EACCES => 13,
            EFAULT => 14,
            EBUSY => 16,
            EEXIST => 17,
            EXDEV => 18,
            ENODEV => 19,
            ENOTDIR => 20,
            EISDIR => 21,
            EINVAL => 22,
            ENFILE => 23,
            EMFILE => 24,
            ENOTTY => 25,
            EFBIG => 27,
            ENOSPC => 28,
            ESPIPE => 29,
            EROFS => 30,
            EMLINK => 31,
            EPIPE => 32,
            EDOM => 33,
            ERANGE => 34,
            EDEADLK => 36,
            EDEADLOCK => 36,
            ENAMETOOLONG => 38,
            ENOLCK => 39,
            ENOSYS => 40,
            ENOTEMPTY => 41,
            EILSEQ => 42,
            EADDRINUSE => 100,
            EADDRNOTAVAIL => 101,
            EAFNOSUPPORT => 102,
            EALREADY => 103,
            EBADMSG => 104,
            ECANCELED => 105,
            ECONNABORTED => 106,
            ECONNREFUSED => 107,
            ECONNRESET => 108,
            EDESTADDRREQ => 109,
            EHOSTUNREACH => 110,
            EIDRM => 111,
            EINPROGRESS => 112,
            EISCONN => 113,
            ELOOP => 114,
            EMSGSIZE => 115,
            ENETDOWN => 116,
            ENETRESET => 117,
            ENETUNREACH => 118,
            ENOBUFS => 119,
            ENODATA => 120,
            ENOLINK => 121,
            ENOMSG => 122,
            ENOPROTOOPT => 123,
            ENOTCONN => 124,
            ENOTRECOVERABLE => 125,
            ENOTSOCK => 126,
            ENOTSUP => 127,
            EOPNOTSUPP => 127,
            EOVERFLOW => 128,
            EOWNERDEAD => 129,
            EPROTO => 130,
            EPROTONOSUPPORT => 131,
            EPROTOTYPE => 132,
            ETIMEDOUT => 133,
            ETXTBSY => 134,
        );
    } else {
        # Linux errno values (default)
        %err = (
	EPERM => 1,
	ENOENT => 2,
	ESRCH => 3,
	EINTR => 4,
	EIO => 5,
	ENXIO => 6,
	E2BIG => 7,
	ENOEXEC => 8,
	EBADF => 9,
	ECHILD => 10,
	EAGAIN => 11,
	EWOULDBLOCK => 11,
	ENOMEM => 12,
	EACCES => 13,
	EFAULT => 14,
	ENOTBLK => 15,
	EBUSY => 16,
	EEXIST => 17,
	EXDEV => 18,
	ENODEV => 19,
	ENOTDIR => 20,
	EISDIR => 21,
	EINVAL => 22,
	ENFILE => 23,
	EMFILE => 24,
	ENOTTY => 25,
	ETXTBSY => 26,
	EFBIG => 27,
	ENOSPC => 28,
	ESPIPE => 29,
	EROFS => 30,
	EMLINK => 31,
	EPIPE => 32,
	EDOM => 33,
	ERANGE => 34,
	EDEADLK => 35,
	EDEADLOCK => 35,
	ENAMETOOLONG => 36,
	ENOLCK => 37,
	ENOSYS => 38,
	ENOTEMPTY => 39,
	ELOOP => 40,
	ENOMSG => 42,
	EIDRM => 43,
	ECHRNG => 44,
	EL2NSYNC => 45,
	EL3HLT => 46,
	EL3RST => 47,
	ELNRNG => 48,
	EUNATCH => 49,
	ENOCSI => 50,
	EL2HLT => 51,
	EBADE => 52,
	EBADR => 53,
	EXFULL => 54,
	ENOANO => 55,
	EBADRQC => 56,
	EBADSLT => 57,
	EBFONT => 59,
	ENOSTR => 60,
	ENODATA => 61,
	ETIME => 62,
	ENOSR => 63,
	ENONET => 64,
	ENOPKG => 65,
	EREMOTE => 66,
	ENOLINK => 67,
	EADV => 68,
	ESRMNT => 69,
	ECOMM => 70,
	EPROTO => 71,
	EMULTIHOP => 72,
	EDOTDOT => 73,
	EBADMSG => 74,
	EOVERFLOW => 75,
	ENOTUNIQ => 76,
	EBADFD => 77,
	EREMCHG => 78,
	ELIBACC => 79,
	ELIBBAD => 80,
	ELIBSCN => 81,
	ELIBMAX => 82,
	ELIBEXEC => 83,
	EILSEQ => 84,
	ERESTART => 85,
	ESTRPIPE => 86,
	EUSERS => 87,
	ENOTSOCK => 88,
	EDESTADDRREQ => 89,
	EMSGSIZE => 90,
	EPROTOTYPE => 91,
	ENOPROTOOPT => 92,
	EPROTONOSUPPORT => 93,
	ESOCKTNOSUPPORT => 94,
	ENOTSUP => 95,
	EOPNOTSUPP => 95,
	EPFNOSUPPORT => 96,
	EAFNOSUPPORT => 97,
	EADDRINUSE => 98,
	EADDRNOTAVAIL => 99,
	ENETDOWN => 100,
	ENETUNREACH => 101,
	ENETRESET => 102,
	ECONNABORTED => 103,
	ECONNRESET => 104,
	ENOBUFS => 105,
	EISCONN => 106,
	ENOTCONN => 107,
	ESHUTDOWN => 108,
	ETOOMANYREFS => 109,
	ETIMEDOUT => 110,
	ECONNREFUSED => 111,
	EHOSTDOWN => 112,
	EHOSTUNREACH => 113,
	EALREADY => 114,
	EINPROGRESS => 115,
	ESTALE => 116,
	EUCLEAN => 117,
	ENOTNAM => 118,
	ENAVAIL => 119,
	EISNAM => 120,
	EREMOTEIO => 121,
	EDQUOT => 122,
	ENOMEDIUM => 123,
	EMEDIUMTYPE => 124,
	ECANCELED => 125,
	ENOKEY => 126,
	EKEYEXPIRED => 127,
	EKEYREVOKED => 128,
	EKEYREJECTED => 129,
	EOWNERDEAD => 130,
	ENOTRECOVERABLE => 131,
	ERFKILL => 132,
	EHWPOISON => 133,
        );
    }
    # Generate proxy constant subroutines for all the values.
    # Well, almost all the values. Unfortunately we can't assume that at this
    # point that our symbol table is empty, as code such as if the parser has
    # seen code such as C<exists &Errno::EINVAL>, it will have created the
    # typeglob.
    # Doing this before defining @EXPORT_OK etc means that even if a platform is
    # crazy enough to define EXPORT_OK as an error constant, everything will
    # still work, because the parser will upgrade the PCS to a real typeglob.
    # We rely on the subroutine definitions below to update the internal caches.
    # Don't use %each, as we don't want a copy of the value.
    foreach my $name (keys %err) {
        if ($Errno::{$name}) {
            # We expect this to be reached fairly rarely, so take an approach
            # which uses the least compile time effort in the common case:
            eval "sub $name() { $err{$name} }; 1" or die $@;
        } else {
            $Errno::{$name} = \$err{$name};
        }
    }
}

our @EXPORT_OK = keys %err;

# Filter POSIX tag to only include constants that exist on this platform
our %EXPORT_TAGS = (
    POSIX => [grep { exists $err{$_} } qw(
	E2BIG EACCES EADDRINUSE EADDRNOTAVAIL EAFNOSUPPORT EAGAIN EALREADY
	EBADF EBUSY ECHILD ECONNABORTED ECONNREFUSED ECONNRESET EDEADLK
	EDESTADDRREQ EDOM EDQUOT EEXIST EFAULT EFBIG EHOSTDOWN EHOSTUNREACH
	EINPROGRESS EINTR EINVAL EIO EISCONN EISDIR ELOOP EMFILE EMLINK
	EMSGSIZE ENAMETOOLONG ENETDOWN ENETRESET ENETUNREACH ENFILE ENOBUFS
	ENODEV ENOENT ENOEXEC ENOLCK ENOMEM ENOPROTOOPT ENOSPC ENOSYS ENOTBLK
	ENOTCONN ENOTDIR ENOTEMPTY ENOTSOCK ENOTTY ENXIO EOPNOTSUPP EPERM
	EPFNOSUPPORT EPIPE EPROTONOSUPPORT EPROTOTYPE ERANGE EREMOTE ERESTART
	EROFS ESHUTDOWN ESOCKTNOSUPPORT ESPIPE ESRCH ESTALE ETIMEDOUT
	ETOOMANYREFS ETXTBSY EUSERS EWOULDBLOCK EXDEV
    )],
);

sub TIEHASH { bless \%err }

sub FETCH {
    my (undef, $errname) = @_;
    return "" unless exists $err{$errname};
    my $errno = $err{$errname};
    return $errno == $! ? $errno : 0;
}

sub STORE {
    require Carp;
    Carp::confess("ERRNO hash is read only!");
}

# This is the true return value
*CLEAR = *DELETE = \*STORE; # Typeglob aliasing uses less space

sub NEXTKEY {
    each %err;
}

sub FIRSTKEY {
    my $s = scalar keys %err;	# initialize iterator
    each %err;
}

sub EXISTS {
    my (undef, $errname) = @_;
    exists $err{$errname};
}

sub _tie_it {
    tie %{$_[0]}, __PACKAGE__;
}

1;

__END__

=head1 NAME

Errno - System errno constants

=head1 SYNOPSIS

    use Errno qw(EINTR EIO :POSIX);

=head1 DESCRIPTION

C<Errno> defines and conditionally exports all the error constants
defined in your system F<errno.h> include file. It has a single export
tag, C<:POSIX>, which will export all POSIX defined error numbers.

On Windows, C<Errno> also defines and conditionally exports all the
Winsock error constants defined in your system F<WinError.h> include
file. These are included in a second export tag, C<:WINSOCK>.

C<Errno> also makes C<%!> magic such that each element of C<%!> has a
non-zero value only if C<$!> is set to that value. For example:

    my $fh;
    unless (open($fh, "<", "/fangorn/spouse")) {
        if ($!{ENOENT}) {
            warn "Get a wife!\n";
        } else {
            warn "This path is barred: $!";
        } 
    } 

If a specified constant C<EFOO> does not exist on the system, C<$!{EFOO}>
returns C<"">.  You may use C<exists $!{EFOO}> to check whether the
constant is available on the system.

Perl automatically loads C<Errno> the first time you use C<%!>, so you don't
need an explicit C<use>.

=head1 CAVEATS

Importing a particular constant may not be very portable, because the
import will fail on platforms that do not have that constant.  A more
portable way to set C<$!> to a valid value is to use:

    if (exists &Errno::EFOO) {
        $! = &Errno::EFOO;
    }

=head1 AUTHOR

Graham Barr <gbarr@pobox.com>

=head1 COPYRIGHT

Copyright (c) 1997-8 Graham Barr. All rights reserved.
This program is free software; you can redistribute it and/or modify it
under the same terms as Perl itself.

=cut

# ex: set ro:
