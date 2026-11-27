use strict;
use warnings;
use Test::More tests => 4;

# `my (undef, ...) = @_` copies $_[0] only to discard it. While the callee
# still runs, the discarded copy must not appear as an extra reference to a
# referent that the caller passed in, including on the first call.

my $code = sub { 1 };
my $closure = sub { $code };

sub discard_first { my (undef, $unused) = @_; &Internals::SvREFCNT($_[0]) }
sub read_second   { my $unused = $_[1];       &Internals::SvREFCNT($_[0]) }

my $baseline = read_second($code, 1);
my @discarding = map { discard_first($code, 1) } 1 .. 3;

is($discarding[0], $baseline, 'first call does not count the discarded copy');
is($discarding[1], $baseline, 'second call does not count the discarded copy');
is($discarding[2], $baseline, 'third call does not count the discarded copy');
is(read_second($code, 1), $baseline, 'count is unchanged after the calls');
