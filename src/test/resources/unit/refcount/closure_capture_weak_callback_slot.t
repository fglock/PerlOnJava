use strict;
use warnings;

use Scalar::Util qw(weaken);
use Test::More;

my $source = bless { callbacks => [] }, 'WeakCallbackSource';
my $holder = bless {}, 'WeakCallbackHolder';
my $completed = 0;

{
    my $sequence = bless {}, 'WeakCallbackSequence';
    push @{$source->{callbacks}}, [sub { $completed = 1 }, $sequence];
    weaken($source->{callbacks}[-1][1]);

    # This is the same ownership shape as Future's cancellation callback: a
    # live callback captures the sequence while the source stores only a weak
    # callback slot for it.
    $holder->{on_cancel} = sub { $sequence };
}

my $callback = $source->{callbacks}[0][0];
ok(defined($source->{callbacks}[0][1]),
    'captured callback keeps its weak callback-slot target alive');
$callback->();
is($completed, 1, 'source dispatch observes the retained sequence callback');

undef $holder;
ok(!defined($source->{callbacks}[0][1]),
    'weak callback slot clears after its final capture owner is released');

done_testing;
