#!perl -T
use strict;
use warnings;
use File::Temp qw(tempfile);
use Scalar::Util qw(tainted);
use Test::More;

my ($fh, $path) = tempfile();
print {$fh} "external input\n";
seek $fh, 0, 0 or die "seek $path: $!";
my $line = <$fh>;
ok(tainted($line), 'readline marks file input as tainted under -T');

seek $fh, 0, 0 or die "seek $path: $!";
my $buffer = '';
read($fh, $buffer, 4);
ok(tainted($buffer), 'read marks file input as tainted under -T');
close $fh;

opendir my $dh, '.' or die "opendir .: $!";
my $entry = readdir $dh;
ok(tainted($entry), 'readdir marks directory input as tainted under -T');
closedir $dh;

sub perlsec_is_tainted {
    return !eval { eval('#' . substr(join('', @_), 0, 0)); 1 };
}

ok(
    perlsec_is_tainted($entry),
    'tainted eval STRING is rejected using the standard perlsec idiom',
);

done_testing;
