use strict;
use warnings;

use Scalar::Util qw(weaken);
use Test::More tests => 2;

sub make_queue {
    my $value = 1;
    my $strong = \$value;
    my %weak;

    weaken($weak{value} = $strong);

    return (\%weak, [ [ value => $strong ] ]);
}

my ($weak, $queue) = make_queue();
ok(defined $weak->{value}, 'queued reference keeps the referent alive');

my $key = (shift @$queue)->[0];
ok(!defined $weak->{$key}, 'shift temporary is released at the statement boundary');
