use strict;
use warnings;
use Test::More tests => 3;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, shift };
    my $ok = eval 'my $duplicate; my $duplicate; 1';
    ok($ok, 'eval STRING compiles and runs despite a non-fatal warning');
}

is(scalar @warnings, 1, 'compile-time warning was delivered to __WARN__');
like($warnings[0], qr/variable \$duplicate masks earlier declaration/,
    '__WARN__ received the duplicate lexical warning');
