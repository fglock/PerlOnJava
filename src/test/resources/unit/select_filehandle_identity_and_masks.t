use strict;
use warnings;
use Test::More tests => 12;

open my $fh, '>', \(my $buffer) or die "open scalar handle: $!";
is(select($fh), 'main::STDOUT', 'select returns the previous standard handle');
my $selected = select();
ok(ref($selected), 'select returns a reference for the selected lexical handle');
is($selected, $fh, 'select preserves the selected glob identity');
is(select(STDOUT), $fh, 'select returns the same glob when switching back');
is(select(), 'main::STDOUT', 'select restores the standard handle name');
is(ref \select, 'SCALAR', 'the standard handle result remains a string scalar');

my $error = eval { CORE::select('a', '', '', 0); 1 } ? '' : $@;
like($error, qr/^Modification of a read-only value attempted/,
    'select rejects a read-only read mask');
$error = eval { CORE::select('', 'a', '', 0); 1 } ? '' : $@;
like($error, qr/^Modification of a read-only value attempted/,
    'select rejects a read-only write mask');
$error = eval { CORE::select('', '', 'a', 0); 1 } ? '' : $@;
like($error, qr/^Modification of a read-only value attempted/,
    'select rejects a read-only exception mask');

SKIP: {
    skip 'wide-character select mask validation was added after this Perl', 3
        if $] < 5.043;
    my $badmask = "\x{100}";
    $error = eval { CORE::select($badmask, undef, undef, 0); 1 } ? '' : $@;
    ok($error, 'select rejects a wide-character read mask');
    $error = eval { CORE::select(undef, $badmask, undef, 0); 1 } ? '' : $@;
    ok($error, 'select rejects a wide-character write mask');
    $error = eval { CORE::select(undef, undef, $badmask, 0); 1 } ? '' : $@;
    ok($error, 'select rejects a wide-character exception mask');
}
