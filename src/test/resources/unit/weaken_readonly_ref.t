use strict;
use warnings;
use Test::More tests => 1;

no warnings 'experimental::builtin';
use builtin qw(weaken);

sub WeakenReadonlyRef::DESTROY {
    eval { weaken($_[0]) };
    like($@, qr/^Modification of a read-only/, 'weaken refuses a read-only DESTROY invocant');
}

my $obj = bless [], 'WeakenReadonlyRef';
undef $obj;
