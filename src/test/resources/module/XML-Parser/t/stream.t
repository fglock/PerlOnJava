use strict;
use warnings;
use Test::More tests => 3;

use XML::Parser;

my $delim   = '------------123453As23lkjlklz877';
my $file    = 'samples/REC-xml-19980210.xml';
my $tmpfile = 'stream.tmp';

my $cnt = 0;

open( my $out_fh, '>', $tmpfile ) or die "Couldn't open $tmpfile for output";
open( my $in_fh,  '<', $file )    or die "Couldn't open $file for input";

while (<$in_fh>) {
    print $out_fh $_;
}

close($in_fh);
print $out_fh "$delim\n";

open( $in_fh, '<', $file ) or die "Couldn't reopen $file for input";
while (<$in_fh>) {
    print $out_fh $_;
}

close($in_fh);
close($out_fh);

my $parser = XML::Parser->new(
    Stream_Delimiter => $delim,
    Handlers         => {
        Comment => sub { $cnt++; }
    }
);

open( my $fh, '<', $tmpfile ) or die "Couldn't open $tmpfile for reading";

$parser->parse($fh);

is( $cnt, 37, 'first parse of delimited stream finds 37 comments' );

$cnt = 0;

$parser->parse($fh);

is( $cnt, 37, 'second parse of delimited stream finds 37 comments' );

close($fh);
unlink($tmpfile);

ok( !-f $tmpfile, 'temp file cleaned up' );
