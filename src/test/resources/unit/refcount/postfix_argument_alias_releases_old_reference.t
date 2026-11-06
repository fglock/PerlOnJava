use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

our $destroyed = 0;

{
    package PostfixArgumentOwner;
    use overload '0+' => sub { 1 }, fallback => 1;
    sub DESTROY { ++$main::destroyed }
}

sub postfix_argument { $_[0]++ }
sub postfix_argument_decrement { $_[0]-- }

my ($weak, $old);
{
    my $object = bless {}, 'PostfixArgumentOwner';
    $weak = $object;
    weaken($weak);

    $old = postfix_argument($object);
    is(ref($old), 'PostfixArgumentOwner',
       'postfix through an argument alias returns the old reference');
    ok(!ref($object), 'postfix through an argument alias updates the caller scalar');
    ok(defined $weak, 'the returned old reference keeps its referent alive');
}

ok(defined $weak, 'postfix result retains the referent after source scope exit');
undef $old;
ok(!defined $weak, 'dropping the postfix result releases the old referent');
is($destroyed, 1, 'postfix referent is destroyed exactly once');

my ($weak_decrement, $old_decrement);
{
    my $object = bless {}, 'PostfixArgumentOwner';
    $weak_decrement = $object;
    weaken($weak_decrement);

    $old_decrement = postfix_argument_decrement($object);
    is(ref($old_decrement), 'PostfixArgumentOwner',
       'postfix decrement through an argument alias returns the old reference');
    ok(!ref($object), 'postfix decrement through an argument alias updates the caller scalar');
    ok(defined $weak_decrement, 'the returned old reference keeps its referent alive');
}

ok(defined $weak_decrement, 'postfix decrement result retains the referent after source scope exit');
undef $old_decrement;
ok(!defined $weak_decrement, 'dropping the postfix decrement result releases the old referent');
is($destroyed, 2, 'postfix increment and decrement referents are each destroyed once');

done_testing();
