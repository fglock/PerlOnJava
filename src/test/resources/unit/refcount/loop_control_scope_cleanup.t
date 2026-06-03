use strict;
use warnings;
use Test::More tests => 6;
use Scalar::Util qw(refaddr weaken);

our (%WEAK, $DESTROYED);

{
    package LoopControlCleanupProbe;
    sub DESTROY {
        delete $main::WEAK{Scalar::Util::refaddr($_[0])};
        $main::DESTROYED++;
    }
}

for (1 .. 5) {
    my $obj = bless {}, 'LoopControlCleanupProbe';
    weaken($WEAK{refaddr($obj)} = $obj);
    next;
}

is scalar(keys %WEAK), 0, 'next cleans lexicals before continuing';
is $DESTROYED, 5, 'next fires DESTROY for each skipped scope';

%WEAK = ();
$DESTROYED = 0;

while (1) {
    my $obj = bless {}, 'LoopControlCleanupProbe';
    weaken($WEAK{refaddr($obj)} = $obj);
    last;
}

is scalar(keys %WEAK), 0, 'last cleans lexicals before leaving loop';
is $DESTROYED, 1, 'last fires DESTROY for skipped scope';

%WEAK = ();
$DESTROYED = 0;

my $i = 0;
while ($i < 3) {
    $i++;
    my $obj = bless {}, 'LoopControlCleanupProbe';
    weaken($WEAK{refaddr($obj)} = $obj);
    redo if $i < 3;
}

is scalar(keys %WEAK), 0, 'redo cleans lexicals before restarting block';
is $DESTROYED, 3, 'redo fires DESTROY for each restarted scope';
