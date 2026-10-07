use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
use Tie::File;

my ($fh, $path) = tempfile();
print {$fh} "first\nsecond\n";
seek($fh, 0, 2) or die "seek to end: $!";

tie my @lines, 'Tie::File', $fh or die 'tie filehandle';
is($lines[0], 'first', 'reads records from an open filehandle');
$lines[1] = 'updated';
push @lines, 'third';

seek($fh, 0, 0) or die "seek to start: $!";
my $contents = do { local $/; <$fh> };
is($contents, "first\nupdated\nthird\n", 'writes updates through the same filehandle');

untie @lines;
close $fh;
unlink $path;
done_testing;
