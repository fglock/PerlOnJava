package threads::shared;

use strict;
use warnings;

our $VERSION = '1.74';
our $threads_shared = 1;
our $clone_warn;
our @EXPORT = qw(share is_shared shared_clone cond_wait cond_timedwait
                 cond_signal cond_broadcast);

sub _active_share (\[$@%]) { return _share($_[0]) }
sub _active_is_shared (\[$@%]) { return _is_shared($_[0]) }
sub _active_cond_wait (\[$@%];\[$@%]) { return _cond_wait(@_) }
sub _active_cond_timedwait (\[$@%]$;\[$@%]) { return _cond_timedwait(@_) }
sub _active_cond_signal (\[$@%]) { return _cond_signal($_[0]) }
sub _active_cond_broadcast (\[$@%]) { return _cond_broadcast($_[0]) }

if ($threads::threads) {
    *share = \&_active_share;
    *is_shared = \&_active_is_shared;
    *cond_wait = \&_active_cond_wait;
    *cond_timedwait = \&_active_cond_timedwait;
    *cond_signal = \&_active_cond_signal;
    *cond_broadcast = \&_active_cond_broadcast;
} else {
    eval <<'_NO_THREADS_';
sub share          (\[$@%])         { return $_[0] }
sub is_shared      (\[$@%])         { undef }
sub cond_wait      (\[$@%];\[$@%])  { undef }
sub cond_timedwait (\[$@%]$;\[$@%]) { undef }
sub cond_signal    (\[$@%])         { undef }
sub cond_broadcast (\[$@%])         { undef }
_NO_THREADS_
}

sub _id (\[$@%]) { return _shared_id($_[0]) }
sub _refcnt (\[$@%]) { return _shared_refcnt($_[0]) }
sub shared_clone {
    require Carp;
    Carp::croak('Usage: shared_clone(REF)') unless @_ == 1;

    require Scalar::Util;
    my $type = Scalar::Util::reftype($_[0]);
    if (defined($type) && ($type eq 'GLOB' || $type eq 'CODE')) {
        my $message = "Unsupported ref type: $type";
        if (!defined($clone_warn)) {
            Carp::croak($message);
        }
        Carp::carp($message) if $clone_warn;
        return undef;
    }

    return $threads::threads ? _shared_clone($_[0]) : $_[0];
}

sub import {
    my $caller = caller;
    no strict 'refs';
    for my $name (@EXPORT) {
        *{"${caller}::$name"} = \&{$name};
    }
}

1;
