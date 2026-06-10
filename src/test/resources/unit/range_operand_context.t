use strict;
use warnings;
use Test::More tests => 6;

our @contexts;

sub probe {
    push @contexts, defined(wantarray) ? (wantarray ? 'list' : 'scalar') : 'void';
    return shift;
}

my @range = 1 .. probe(2);
is_deeply(\@contexts, ['scalar'], 'right range endpoint is scalar context');
is_deeply(\@range, [1, 2], 'range still expands in list context');

@contexts = ();
@range = probe(2) .. 3;
is_deeply(\@contexts, ['scalar'], 'left range endpoint is scalar context');
is_deeply(\@range, [2, 3], 'left endpoint value is used');

@contexts = ();
my $count = 0;
$count++ for 1 .. probe(2);
is_deeply(\@contexts, ['scalar'], 'foreach range endpoint is scalar context');
is($count, 2, 'foreach iterates over generated range');
