use strict;
use warnings;
use Test::More;

eval {
    require Term::Size;
    Term::Size->import(qw(chars pixels));
    1;
} or plan skip_all => 'Term::Size required';

require PerlIO;

pipe my $rd, my $wr or die "pipe: $!";

my @chars = chars($rd);
is(scalar(@chars), 0, 'Term::Size::chars returns empty list for non-tty handle');
is(scalar(chars($rd)), undef, 'Term::Size::chars returns undef in scalar context for non-tty handle');

my @pixels = pixels($rd);
is(scalar(@pixels), 0, 'Term::Size::pixels returns empty list for non-tty handle');
is(scalar(pixels($rd)), undef, 'Term::Size::pixels returns undef in scalar context for non-tty handle');

my @layers = PerlIO::get_layers(STDOUT);
ok(!grep { $_ eq 'utf8' } @layers, 'PerlIO::get_layers does not report utf8 by default');

done_testing;
