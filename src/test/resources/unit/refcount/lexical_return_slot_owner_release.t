use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);
our $destroyed=0;
{ package LexicalReturnOwner; sub DESTROY { ++$main::destroyed } }
sub make { my $value=bless {}, 'LexicalReturnOwner'; $value }
my $object=make();my $weak=$object;weaken($weak);undef $object;
ok(!defined $weak,'return from the lexical register releases its owner');
is($destroyed,1,'returned lexical referent destroyed exactly once');
sub make_named {
    my ($name) = @_;
    my $value = bless { name => $name }, 'LexicalReturnOwner';
    return $value;
}
$object = make_named('later-local');
$weak = $object;
weaken($weak);
undef $object;
ok(!defined $weak, 'earlier local cleanup preserves the later lexical owner until its release');
is($destroyed, 2, 'a return shared with a later lexical slot is destroyed once');
sub make_named_implicit {
    my ($name) = @_;
    my $value = bless { name => $name }, 'LexicalReturnOwner';
    $value;
}
$object = make_named_implicit('implicit-later-local');
$weak = $object;
weaken($weak);
undef $object;
ok(!defined $weak, 'implicit return also uses a separate result register');
is($destroyed, 3, 'multi-local implicit return releases its owner once');
done_testing();
