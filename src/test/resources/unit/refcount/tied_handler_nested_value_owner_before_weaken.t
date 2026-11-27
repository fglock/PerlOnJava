use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

{
    package PreWeakenTieHandler;
    sub TIEHASH { $_[1] }
    sub FETCH { $_[0]->{values}{$_[1]} }
    sub STORE { $_[0]->{values}{$_[1]} = $_[2] }
}

my $target = bless {}, 'PreWeakenTieTarget';
my $handler = bless { values => { target => $target } }, 'PreWeakenTieHandler';
my %tied;
tie %tied, 'PreWeakenTieHandler', $handler;

my $weak_target = $target;
weaken($weak_target);
undef $target;
undef $handler;

ok(defined $weak_target,
   'a nested tied-handler value stored before first weaken remains strongly owned');

untie %tied;
ok(!defined $weak_target,
   'releasing the tied handler clears the weak observer after the last nested owner leaves');

done_testing();
