use strict;
use warnings;
use Test::More tests => 4;

{
    package Local::Delay;
    sub new { bless {}, shift }
    sub delay { $_[0]{delay} = $_[1] if @_ > 1; $_[0]{delay} }
}

my $q = Local::Delay->new;
is(.5, 0.5, 'leading-dot literal is numeric');
is($q->delay(.5), .5, 'leading-dot literal in method nested in prototyped call');
is(($q->delay(.25)), .25, 'parenthesized method result remains one argument');
is(1 + .5, 1.5, 'leading-dot literal after binary operator');
