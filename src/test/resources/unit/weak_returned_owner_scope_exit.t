use strict;
use warnings;
use Test::More tests => 3;
use Scalar::Util qw(weaken isweak);

my $weak_owner;

sub make_returned_owner {
    my $owner = [];
    $weak_owner = $owner;
    weaken($weak_owner);
    return $owner;
}

my $strong_owner = make_returned_owner();
ok(isweak($weak_owner), 'weak observer remains weak across the return');
ok(defined($weak_owner), 'returned strong owner keeps the referent alive');

undef $strong_owner;
ok(!defined($weak_owner), 'weak observer clears when the returned owner is released');
