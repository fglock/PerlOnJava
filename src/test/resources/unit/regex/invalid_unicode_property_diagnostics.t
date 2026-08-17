use strict;
use warnings;
use Test::More tests => 39;

my $callback_calls = 0;
sub isfoo { $callback_calls++; return "0041" }
sub infoo { $callback_calls++; return "0041" }
sub ISfoo { $callback_calls++; return "0041" }
sub INfoo { $callback_calls++; return "0041" }
sub IsWhitespaceUpper { return "0041" }

my @warnings;
local $SIG{__WARN__} = sub { push @warnings, @_ };
local $ENV{JPERL_UNIMPLEMENTED} = 'warn';

for my $property (qw(q qrst f foo isfoo infoo ISfoo INfoo)) {
    my $source = 'qr/\p{' . $property . '}/';
    my $ok = eval "$source; 1";
    ok(!$ok, "$property is rejected");
    like($@, qr/^Can't find Unicode property definition/, "$property reports the Perl diagnostic");
}

for my $source (qw(qr/\pq/ qr/\Pq/ qr/\pf/ qr/\Pf/)) {
    my $ok = eval "$source; 1";
    ok(!$ok, "$source is rejected");
    like($@, qr/^Can't find Unicode property definition/, "$source reports the Perl diagnostic");
}

for my $property (qw(Is::foo In::foo main::Is::foo pkg::In::foo utf8::Is::foo)) {
    my $source = 'qr/\p{' . $property . '}/';
    my $ok = eval "$source; 1";
    ok(!$ok, "$property is rejected");
    like($@, qr/^Illegal user-defined property name/, "$property reports an illegal name");
}

is($callback_calls, 0, 'noncanonical case variants do not call Perl property subs');
is(scalar @warnings, 0, 'fatal property diagnostics are not downgraded to warnings');

my $spaced = qr/\p{ main::IsWhitespaceUpper }/;
ok($spaced, 'canonical user property permits surrounding whitespace');
ok('A' =~ $spaced, 'spaced user property matches its definition');
ok('a' !~ $spaced, 'spaced user property remains case-sensitive');
