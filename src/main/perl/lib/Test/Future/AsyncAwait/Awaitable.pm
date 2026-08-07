package Test::Future::AsyncAwait::Awaitable;

use v5.14;
use warnings;

our $VERSION = '0.71';

use Test2::V0;
use Exporter 'import';

our @EXPORT_OK = qw(test_awaitable);

sub test_awaitable {
    my ($title, %args) = @_;

    my $class  = $args{class};
    my $new    = $args{new} || sub { $class->new };
    my $cancel = $args{cancel};
    my $force  = $args{force};

    subtest "$title immediate done" => sub {
        my $future = $class->AWAIT_NEW_DONE('result');
        ok($future, 'AWAIT_NEW_DONE yields object');
        ok($future->AWAIT_IS_READY, 'AWAIT_IS_READY true');
        ok(!$future->AWAIT_IS_CANCELLED, 'AWAIT_IS_CANCELLED false');
        is([$future->AWAIT_GET], ['result'], 'AWAIT_GET in list context');
        is(scalar $future->AWAIT_GET, 'result', 'AWAIT_GET in scalar context');
        ok(defined eval { $future->AWAIT_GET; 1 }, 'AWAIT_GET in void context');
    };

    subtest "$title immediate fail" => sub {
        my $future = $class->AWAIT_NEW_FAIL('Oopsie');
        ok($future, 'AWAIT_NEW_FAIL yields object');
        ok($future->AWAIT_IS_READY, 'AWAIT_IS_READY true');
        ok(!$future->AWAIT_IS_CANCELLED, 'AWAIT_IS_CANCELLED false');
        ok(!defined eval { $future->AWAIT_GET; 1 }, 'AWAIT_GET throws in void context');
        like($@, qr/^Oopsie/, 'AWAIT_GET preserves failure');
    };

    my $prototype = $new->() or BAIL_OUT('new did not yield an instance');

    subtest "$title deferred done" => sub {
        my $future = $prototype->AWAIT_CLONE;
        ok($future, 'AWAIT_CLONE yields object');
        ok(!$future->AWAIT_IS_READY, 'AWAIT_IS_READY false');
        $future->AWAIT_DONE('Late result');
        ok($future->AWAIT_IS_READY, 'AWAIT_IS_READY true');
        is(scalar $future->AWAIT_GET, 'Late result', 'AWAIT_GET returns deferred result');
    };

    subtest "$title deferred fail" => sub {
        my $future = $prototype->AWAIT_CLONE;
        ok($future, 'AWAIT_CLONE yields object');
        ok(!$future->AWAIT_IS_READY, 'AWAIT_IS_READY false');
        $future->AWAIT_FAIL('Late oopsie');
        ok($future->AWAIT_IS_READY, 'AWAIT_IS_READY true');
        ok(!defined eval { $future->AWAIT_GET; 1 }, 'AWAIT_GET throws in void context');
        like($@, qr/^Late oopsie/, 'AWAIT_GET preserves deferred failure');
    };

    subtest "$title on-ready" => sub {
        my $future = $new->() or BAIL_OUT('new did not yield an instance');
        my $called;
        $future->AWAIT_ON_READY(sub { $called++ });
        ok(!$called, 'AWAIT_ON_READY callback is pending');
        $future->AWAIT_DONE('ping');
        $force->($future) if $force;
        ok($called, 'AWAIT_ON_READY callback was invoked');
    };

    $cancel and subtest "$title cancellation" => sub {
        my $first = $new->() or BAIL_OUT('new did not yield an instance');
        my $second = $first->AWAIT_CLONE;
        $first->AWAIT_CHAIN_CANCEL($second);
        ok(!$second->AWAIT_IS_CANCELLED, 'chained Future is initially active');
        $cancel->($first);
        ok($second->AWAIT_IS_CANCELLED, 'cancellation propagated');

        my $callback_future = $new->() or BAIL_OUT('new did not yield an instance');
        my $called;
        $callback_future->AWAIT_ON_CANCEL(sub { $called++ });
        $cancel->($callback_future);
        ok($called, 'AWAIT_ON_CANCEL callback was invoked');
    };
}

1;

__END__

=head1 NAME

Test::Future::AsyncAwait::Awaitable - Awaitable API conformance tests

=head1 COPYRIGHT AND LICENSE

Copyright 2020-2024 Paul Evans.

This library is free software; you may redistribute it and/or modify it under
the same terms as Perl itself.

=cut
