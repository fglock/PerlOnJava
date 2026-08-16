use strict;
use warnings;
use Test::More tests => 2;

my $set = { set_number => 7 };
is("target G0.S$set->{\"set_number\"}", 'target G0.S7',
    'escaped quoted key interpolates through a hash reference');

my %set = (set_type => 'xy');
is("type $set{\"set_type\"}", 'type xy',
    'escaped quoted key interpolates through a hash');
