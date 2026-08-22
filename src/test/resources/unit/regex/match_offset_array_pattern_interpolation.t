use strict;
use warnings;
use Test::More tests => 12;

"seed" =~ /(ee)/;

my @start_before = @-;
my @end_before = @+;

my $start_pattern = qr/^X@-A$/;
my $end_pattern = qr/^X@+A$/;

like("X\@-A", $start_pattern,
     '@- remains literal inside a regex pattern');
unlike('X01A', $start_pattern,
       '@- does not interpolate populated match offsets into a pattern');
like("X\@\@A", $end_pattern,
     '@+ remains literal inside a regex pattern');
unlike('X24A', $end_pattern,
       '@+ does not interpolate populated match offsets into a pattern');

is_deeply([@-], \@start_before,
          'compiling offset-array patterns does not alter @-');
is_deeply([@+], \@end_before,
          'compiling offset-array patterns does not alter @+');

is("@-", join(' ', @start_before),
   '@- still interpolates in an ordinary double-quoted string');
is("@+", join(' ', @end_before),
   '@+ still interpolates in an ordinary double-quoted string');

our @piece = ('Y');
like('XY', qr/^X@piece$/,
     'an ordinary named array still interpolates inside a regex');
unlike('X@piece', qr/^X@piece$/,
       'named-array interpolation is not disabled with offset arrays');

like("X\@-A", qr/^X\@-A$/,
     'an explicitly escaped at sign retains the same literal pattern');
like("X\@\@A", qr/^X\@+A$/,
     'an explicitly escaped @+ retains the same literal pattern');
