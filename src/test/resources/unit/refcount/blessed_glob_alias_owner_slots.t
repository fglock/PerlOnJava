use strict;
use warnings;
use Test::More tests => 4;
use Scalar::Util qw(weaken);
use Symbol qw(gensym);

our $destroyed = 0;

{
    package BlessedGlobAliasOwner;
    sub DESTROY { ++$main::destroyed }
}

my $glob = gensym();
open($glob, '<', __FILE__) or die "open test file: $!";
my $object = bless $glob, 'BlessedGlobAliasOwner';
my $weak = $object;
weaken($weak);
my $alias = $object;

undef $object;
ok(defined $weak, 'the blessed glob remains alive through another lexical alias');
is($destroyed, 0, 'the remaining alias prevents early DESTROY');

undef $glob;
ok(defined $weak, 'the blessed object remains alive after the source glob leaves');

undef $alias;
ok(!defined $weak && $destroyed == 1,
   'the final lexical alias releases the blessed glob and runs DESTROY once');

