use strict;
use warnings;
use Scalar::Util qw(weaken isweak);
use Test::More tests => 5;

our $destroyed = 0;
{
    package Local::BlessedGlobAliasOwner;
    sub DESTROY { ++$main::destroyed }
}

my $buffer = '';
my $handle;
open $handle, '>', \$buffer or die "open scalar handle: $!";
my $alias = $handle;
my $weak = $handle;
weaken($weak);
bless $handle, 'Local::BlessedGlobAliasOwner';
ok(isweak($weak), 'observer is weak');

undef $handle;
ok(defined($weak), 'second live glob alias retains the referent');
is($destroyed, 0, 'DESTROY waits for the remaining alias');

undef $alias;
ok(!defined($weak), 'weak observer clears with the last alias');
is($destroyed, 1, 'last alias release dispatches DESTROY once');
