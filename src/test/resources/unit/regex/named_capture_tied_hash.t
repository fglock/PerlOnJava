use strict;
use warnings;
use Test::More tests => 32;

'hlagh' =~ /(?<a>.)(?<b>.)(?<a>.).*(?<e>$)/;

is($+{a}, 'h', '%+ fetches the first participating duplicate capture');
is($-{a}[0], 'h', '%- fetches the first duplicate capture');
is($-{a}[1], 'a', '%- fetches every duplicate capture');

for my $operation (
    sub { $+{a} = 'changed' },
    sub { delete $+{a} },
    sub { %+ = () },
) {
    eval { $operation->() };
    like($@, qr/read-only/, 'named capture hashes are read-only');
}

my $plus = tied %+;
my $minus = tied %-;
isa_ok($plus, 'Tie::Hash::NamedCapture');
isa_ok($minus, 'Tie::Hash::NamedCapture');

is(Tie::Hash::NamedCapture::FETCH(undef, undef), undef, 'FETCH with undef');
eval { Tie::Hash::NamedCapture::STORE(undef, undef, undef) };
like($@, qr/Modification of a read-only value attempted/, 'STORE with undef');
eval { Tie::Hash::NamedCapture::DELETE(undef, undef) };
like($@, qr/Modification of a read-only value attempted/, 'DELETE with undef');
eval { Tie::Hash::NamedCapture::CLEAR(undef) };
like($@, qr/Modification of a read-only value attempted/, 'CLEAR with undef');
is(Tie::Hash::NamedCapture::EXISTS(undef, undef), undef, 'EXISTS with undef');
is(Tie::Hash::NamedCapture::FIRSTKEY(undef), undef, 'FIRSTKEY with undef');
is(Tie::Hash::NamedCapture::NEXTKEY(undef, undef), undef, 'NEXTKEY with undef');
is(Tie::Hash::NamedCapture::SCALAR(undef), undef, 'SCALAR with undef');

for my $case (
    [FETCH => '$key'],
    [STORE => '$key, $value'],
    [DELETE => '$key'],
    [CLEAR => ''],
    [EXISTS => '$key'],
    [FIRSTKEY => ''],
    [NEXTKEY => '$lastkey'],
    [SCALAR => ''],
) {
    my ($method, $signature) = @$case;
    is(eval { $plus->$method(0 .. 3); 1 }, undef, "$method rejects extra arguments");
    like($@, qr/Usage: Tie::Hash::NamedCapture::\Q$method\E\(\Q$signature\E\)/,
        "$method reports its signature");
}
