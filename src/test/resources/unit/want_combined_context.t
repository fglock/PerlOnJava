use strict;
use warnings;
use Test::More tests => 4;

use Want ();

{
    package Local::WantChain;
    sub new { bless {}, shift }
    sub step {
        my ($self) = @_;
        return Want::want('OBJECT', 'SCALAR') ? $self : 'done';
    }
}

my $chain = Local::WantChain->new;
is($chain->step, 'done', 'OBJECT predicate rejects an ordinary scalar result');
is($chain->step->step, 'done', 'combined OBJECT and SCALAR match a chained invocant');
my @list = $chain->step;
is_deeply(\@list, ['done'], 'combined predicates reject list context');
is($chain->step, 'done', 'ordinary scalar context remains stable after a chain');
