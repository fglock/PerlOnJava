use strict;
use warnings;
use Test::More;

our $destroyed = 0;
our $callback_ran = 0;

{
    package Local::Guard;

    sub DESTROY {
        ++$main::destroyed;
        ${$_[0]}->();
    }
}

sub guard {
    bless \(my $callback = shift), 'Local::Guard';
}

my $guard = guard(sub { ++$callback_ran });
$guard = guard(sub { });

is $destroyed, 1, 'overwriting a scalar guard destroys its previous referent';
is $callback_ran, 1, 'the scalar guard callback runs once';

our @released;

{
    package Local::WrappedGuard;

    sub DESTROY { push @main::released, $_[0]->[0] }
}

sub wrapped_guard {
    my ($name) = @_;
    \(my $guard = bless [$name], 'Local::WrappedGuard');
}

my $id = wrapped_guard('first');
$$id = undef;
$id = wrapped_guard('second');

is_deeply \@released, ['first'],
    'writing through a scalar reference releases its old guard referent';
is $$id->[0], 'second', 'the scalar reference receives its replacement guard';

our @returned_releases;

{
    package Local::ReturnedGuard;

    sub DESTROY { ${$_[0]}->() }
}

sub returned_guard {
    my ($name) = @_;
    \(my $guard = bless \(my $callback = sub { push @returned_releases, $name }),
        'Local::ReturnedGuard');
}

my $returned_id = returned_guard('first');
$$returned_id = undef;
$returned_id = returned_guard('second');

is_deeply \@returned_releases, ['first'],
    'a returned scalar reference transfers its guard owner to the caller';
ok defined $$returned_id, 'the replacement returned guard remains alive';
done_testing;
