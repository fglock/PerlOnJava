use strict;
use warnings;
use Test::More;

our $destroyed = 0;

{
    package AbandonedMethodHold;
    sub new { bless {}, shift }
    sub DESTROY { $main::destroyed++ }
    sub invoke { die "method must not run" }
}

my $object = AbandonedMethodHold->new;
my $root = { object => $object };
$object = undef;

eval { $root->{object}->invoke(do { die "argument failed" }); 1 };
like($@, qr/argument failed/, 'argument evaluation aborts the method chain');
is($destroyed, 0,
    'aggregate owner keeps the invocant alive after abandoned hold release');

undef $root->{object};
is($destroyed, 1, 'removing the last aggregate owner runs DESTROY');

done_testing();
