use strict;
use warnings;
use Test::More tests => 4;
use Symbol qw(gensym);

my $self = gensym();
bless $self, 'Local::Socket';

my $old = ${*$self}{io_socket_ip_idx}++;
is($old, 0, 'postincrement returns zero for an undefined glob hash element');
is(${*$self}{io_socket_ip_idx}, 1, 'postincrement stores the incremented glob hash element');

{
    no strict 'refs';
    my $loose_old = ${*$self}{loose_idx}++;
    is($loose_old, 0, 'loose-ref postincrement returns zero for an undefined glob hash element');
    is(${*$self}{loose_idx}, 1, 'loose-ref postincrement stores the incremented glob hash element');
}
