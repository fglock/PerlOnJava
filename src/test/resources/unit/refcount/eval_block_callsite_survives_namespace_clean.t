use strict;
use warnings;
use Test::More tests => 6;

package Issue1649::EvalCallsiteHelper;
use Exporter 'import';
BEGIN { our @EXPORT_OK = qw(bump) }
our $calls = 0;
sub bump { $calls++ }

package Issue1649::EvalCallsiteCaller;
BEGIN { Issue1649::EvalCallsiteHelper->import('bump') }
use namespace::clean;

sub call_inside_eval {
    eval {
        bump();
        die "expected eval exit";
    };
    return $@;
}

package main;
for my $expected (1 .. 3) {
    like(Issue1649::EvalCallsiteCaller::call_inside_eval(), qr/expected eval exit/,
        "eval block call $expected completes");
    is($Issue1649::EvalCallsiteHelper::calls, $expected,
        "compiled callsite remains usable on reuse $expected");
}
