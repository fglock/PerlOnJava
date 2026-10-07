use strict;
use warnings;
use builtin qw(weaken);
use Test::More;

my $weak_subscriber;
{
    my $unused_outer;
    my $subject = sub {
        $unused_outer = $_[0];
        my $inner_lexical;
        return sub { $inner_lexical };
    };
    my $subscriber = {};
    weaken($weak_subscriber = $subscriber);
    $subscriber->{callback} = $subject->($subscriber);
}

ok(!defined $weak_subscriber,
    'a returned closure does not retain an unrelated outer subscriber');

done_testing();
