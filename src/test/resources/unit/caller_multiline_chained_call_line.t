#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 10;

# Perl keeps one COP (source line) per statement, taken from the statement's
# first token, so every call inside a multi-line statement -- including a method
# call at the very end of a chained expression -- reports the line the statement
# started on, not the closing ")->method" line.
#
# Each case below is preceded by an ordinary ";"-terminated statement on purpose:
# after a block-terminated statement ("if (...) { }", a bare block, "{ package
# ... }") Perl's own copline bookkeeping shifts the reported line, and that
# artifact is deliberately not reproduced here.

{
    package CallerMultilineChain::Obj;
    sub new { bless {}, shift }
    sub report { return (caller)[2] }
    sub chain { $_[0] }
}

my $obj = CallerMultilineChain::Obj->new;

sub plain_report { return (caller)[2] }

# 1. the issue's shape: multi-line argument list, then ")->method"
my $expected_chain = __LINE__ + 1;
my $chain = CallerMultilineChain::Obj->new(
    value => 1,
)->report;
is($chain, $expected_chain,
    'method call after a multi-line argument list reports the statement line');

# 2. call finished on one line, arrow continued on the next
my $expected_arrow_next = __LINE__ + 1;
my $arrow_next = CallerMultilineChain::Obj->new(1)
    ->report;
is($arrow_next, $expected_arrow_next,
    'arrow on a continuation line reports the statement line');

# 3. invocant alone on the first line
my $expected_invocant = __LINE__ + 1;
my $invocant = $obj
    ->report;
is($invocant, $expected_invocant,
    'method call on a continuation line reports the statement line');

# 4. deeper chain: every call in the chain shares the statement line
my $expected_deep = __LINE__ + 1;
my $deep = CallerMultilineChain::Obj->new(
)->chain(
)->report;
is($deep, $expected_deep,
    'deep multi-line chain reports the statement line');

# 5. arguments spread over several lines
my $expected_wide = __LINE__ + 1;
my $wide = CallerMultilineChain::Obj->new(
    a => 1,
    b => 2,
    c => 3,
)->report;
is($wide, $expected_wide,
    'method call after a wide argument list reports the statement line');

# 6. plain (non-method) multi-line call
my $expected_plain = __LINE__ + 1;
my $plain = plain_report(
    1,
);
is($plain, $expected_plain,
    'plain multi-line call reports the statement line');

# 7. assignment operator on its own line before the chain
my $expected_assign = __LINE__ + 1;
my $assign =
    CallerMultilineChain::Obj->new(
        1,
    )->report;
is($assign, $expected_assign,
    'chain below an assignment reports the statement line');

# 8. warn uses the same per-statement source line
my @warnings;
local $SIG{__WARN__} = sub { push @warnings, $_[0] };
my $expected_warn = __LINE__ + 1;
warn(
    "multiline warn",
);
like($warnings[0], qr/\bline $expected_warn\b/,
    'multi-line warn reports the statement line');

# 9. single-line calls are unaffected
my $expected_single = __LINE__ + 1;
my $single = $obj->report;
is($single, $expected_single, 'single-line method call reports its own line');

# 10. a literal anon sub argument is still exempt from the statement line
my $expected_block = __LINE__ + 3;
my $block = plain_report(
    sub { 1 }
);
is($block, $expected_block,
    'literal anon sub argument keeps its own block line');
