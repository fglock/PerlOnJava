use strict;
use warnings;
use Test::More tests => 2;

use lib 'src/main/perl/lib';
use Clone qw(clone);

my $value = 0;
my $copy = clone({
    current => \$value,
    diff     => { old => \$value },
});

$copy->{current} = 3;
is($copy->{current}, 3, 'assignment updates the selected hash slot');
is(${$copy->{diff}{old}}, 0,
   'hash slots remain independent when their referents are shared');
