use strict;
use warnings;
use Storable qw(dclone);
use Test::More tests => 3;

our $destroyed = 0;

{
    package Local::DcloneReturnOwner;

    sub new { bless {}, shift }
    sub DESTROY { ++$main::destroyed }
}

my $original = Local::DcloneReturnOwner->new;
my $copy = dclone($original);

is($destroyed, 0, 'dclone does not destroy either live object');

undef $copy;
is($destroyed, 1, 'the cloned object is destroyed when its last Perl owner leaves');

undef $original;
is($destroyed, 2, 'the original object is destroyed independently');
