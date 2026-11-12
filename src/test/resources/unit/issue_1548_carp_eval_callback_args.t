use strict;
use warnings;
use Carp qw(longmess);
use Test::More tests => 2;

sub run_callback_in_eval {
    my ($callback, $expect_return) = @_;
    eval { $callback->() };
}

{
    package Issue1548::Trace;
    sub trace { _trace() }
    sub _trace { _trace_raw() }
    sub _trace_raw { Carp::longmess('trace') }
}

my $trace = '';
run_callback_in_eval(sub {
    $trace = Issue1548::Trace::trace();
}, 0);

like($trace, qr/main::__ANON__\(\) called at .* line \d+/,
    'Carp keeps an eval callback anonymous in a nested helper stack');
like($trace, qr/main::run_callback_in_eval\(CODE\([^)]*\), 0\)/,
    'Carp reports the original callback arguments across eval frames');
