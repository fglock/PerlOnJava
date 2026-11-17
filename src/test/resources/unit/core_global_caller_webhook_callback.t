use strict;
use warnings;
use Test::More tests => 4;

our $webhook_caller_proxy;
BEGIN {
    no warnings 'redefine';
    *CORE::GLOBAL::caller = sub (;$) {
        my ($height) = @_;
        return CORE::caller(($height || 0) + 1);
    };
}

sub dispatch_webhook_callback {
    my ($callback) = @_;
    local $webhook_caller_proxy = *CORE::GLOBAL::caller{CODE};
    local *CORE::GLOBAL::caller = sub (;$) {
        my ($height) = @_;
        return $webhook_caller_proxy->(($height || 0) + 1);
    };
    return $callback->();
}

my $callback_frame = dispatch_webhook_callback(sub { [caller] });
is($callback_frame->[0], 'main', 'webhook callback retains the caller proxy');
like($callback_frame->[1], qr/\Q$0\E/, 'caller proxy retains the webhook source file');

my $callback_error = eval {
    dispatch_webhook_callback(sub { die "webhook callback failure\n" });
    '';
} || $@;
like($callback_error, qr/webhook callback failure/, 'callback exceptions survive caller proxy handling');

sub caller_after_webhook_error {
    return [caller];
}

my $after_error_frame = caller_after_webhook_error();
is($after_error_frame->[0], 'main', 'caller proxy and saved callback restore after webhook errors');
