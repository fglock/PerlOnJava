#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use Carp qw(carp);
use Scalar::Util qw(refaddr weaken);

our @captured_args;
my %destruction_registry;

sub detected_reinvoked_destroy {
    my ($object) = @_;
    my $address = refaddr($object);
    if (!defined $destruction_registry{$address}) {
        $destruction_registry{$address} = $object;
        weaken($destruction_registry{$address});
        return 0;
    }

    carp('Preventing *MULTIPLE* DESTROY() invocations on CapturedTxnGuard');
    return 1;
}

{
    package CapturedTxnGuard::Storage;
    sub new { bless { dbh => bless({}, 'CapturedTxnGuard::DBH') }, shift }
    sub _verify_pid { 1 }
    sub _seems_connected { 1 }
    sub txn_rollback { 1 }
}

{
    package CapturedTxnGuard;

    sub new {
        my ($class, $storage) = @_;
        my $guard = { inactivated => 0, storage => $storage, dbh => $storage->{dbh} };
        Scalar::Util::weaken($guard->{dbh});
        return bless $guard, $class;
    }

    sub DESTROY {
        return if &main::detected_reinvoked_destroy;

        my $self = shift;
        $self->{storage}->_verify_pid;
        return unless $self->{dbh};

        local $@;
        Carp::carp('Guard went out of scope without explicit commit');
        my $rollback_exception;
        eval { $self->{storage}->_seems_connected && $self->{storage}->txn_rollback };
    }
}

{
    local $SIG{__WARN__} = sub {
        package DB;
        my $frame = 0;
        while (my @caller = caller(++$frame)) {
            push @main::captured_args, @DB::args;
        }
    };

    my $storage = CapturedTxnGuard::Storage->new;
    my $guard = CapturedTxnGuard->new($storage);
    undef $guard;
}

ok(grep({ ref($_) eq 'CapturedTxnGuard' } @captured_args),
    'caller() captures the guard from its DESTROY warning');

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    @captured_args = ();
}
like(join('', @warnings),
    qr/Preventing \*MULTIPLE\* DESTROY\(\) invocations on CapturedTxnGuard/,
    're-fire DESTROY when the captured guard reference is released');

done_testing;
