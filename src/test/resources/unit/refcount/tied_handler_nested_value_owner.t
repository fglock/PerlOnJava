use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

{
    package NestedValueTieHandler;
    sub TIEHASH { $_[1] }
    sub FETCH { $_[0]->{values}{$_[1]} }
    sub STORE { $_[0]->{values}{$_[1]} = $_[2] }
}

my $target = bless {}, 'NestedValueTieTarget';
my $weak_target = $target;
weaken($weak_target);
my $handler = bless { values => { target => $target } }, 'NestedValueTieHandler';
my %tied;
tie %tied, 'NestedValueTieHandler', $handler;

undef $target;
undef $handler;
ok(defined $weak_target, 'the tied handler retains values stored in its nested hash');

untie %tied;
ok(!defined $weak_target, 'untie releases the handler and its nested value');

done_testing();
