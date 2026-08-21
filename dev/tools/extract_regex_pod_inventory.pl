#!/usr/bin/env perl
use strict;
use warnings;
use File::Spec;
use FindBin;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..'));
my $perl_root = $ENV{PERLONJAVA_PERL_ROOT} // File::Spec->catdir($root, 'perl5');
my @pods = qw(perlreref.pod perlrecharclass.pod perlrequick.pod perlrepository.pod perlre.pod perlretut.pod perlrebackslash.pod);
for my $pod (@pods) {
    my $path = File::Spec->catfile($perl_root, 'pod', $pod);
    open my $fh, '<', $path or die "Cannot read $path: $!\n";
    my (@headings, %constructs);
    while (my $line = <$fh>) {
        push @headings, $1 if $line =~ /^=head\d\s+(.+)/;
        $constructs{$1}++ while $line =~ /(\(\?[^\s)]+|\(\*[^\s)]+|\\[pPNKkQERX])/g;
    }
    close $fh;
    print "FILE\t$pod\n";
    print "HEADING\t$_\n" for @headings;
    print "CONSTRUCT\t$_\n" for sort keys %constructs;
}
