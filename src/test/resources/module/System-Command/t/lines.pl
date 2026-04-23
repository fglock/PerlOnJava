#!perl
use strict;
use warnings;

my @fh = map { [ $_ => \do { my $ln = 1 } ] } qw( STDOUT STDERR );

for my $lines (@ARGV) {
    no strict 'refs';
    push @fh, [ my ( $nm, $ln ) = @{ shift @fh } ];
    my $fh = \*$nm;
    print $fh join '', $nm, ' line ', $$ln++, "\n" while $lines--;
}

