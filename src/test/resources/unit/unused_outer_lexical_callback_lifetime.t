use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

our $destroyed;
our @callbacks;

{
    package UnusedOuterLexicalLifetime::Owner;
    sub DESTROY { $main::destroyed++ }
}

$destroyed = 0;
{
    my $owner = bless {}, 'UnusedOuterLexicalLifetime::Owner';
    my $weak_owner = $owner;
    weaken($weak_owner);

    my $register_callback = sub {
        my $controller = shift;
        push @main::callbacks, sub {
            my $event = shift;
            return $event;
        };
    };

    $register_callback->($owner);
    undef $owner;
    is($destroyed, 1, 'owner is destroyed when callback does not use outer lexical');
    ok(!defined($weak_owner), 'stored callback does not retain unrelated outer object');
}

is($callbacks[0]->('finished'), 'finished', 'stored callback remains callable');
done_testing;
