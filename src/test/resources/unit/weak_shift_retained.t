use strict;
use warnings;

use Scalar::Util qw(weaken);
use Test::More tests => 2;

sub make_queue {
    my $value = 1;
    my $strong = \$value;
    my %weak = (value => $strong);
    weaken($weak{value});

    return (\%weak, [ [ value => $strong ] ]);
}

my ($weak, $queue) = make_queue();
my $kept = shift @$queue;
ok(defined $weak->{value}, 'shifted container retained in a lexical remains strong');

undef $kept;
ok(!defined $weak->{value}, 'weak referent clears after retained owners are released');
