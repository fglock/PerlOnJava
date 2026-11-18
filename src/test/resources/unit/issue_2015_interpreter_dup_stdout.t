use strict;
use warnings;
use Test::More;
use File::Temp ();

my %captures;
my $capture = ($captures{streams} ||= {})->{stdout} ||= File::Temp->new();

ok(defined fileno($capture),
    'a File::Temp handle stored through nested ||= keeps its descriptor');

my $opened;
{
    $opened = open local *STDOUT, '>&', $capture;
    if ($opened) {
        print STDOUT "captured\n";
    }
}

ok($opened, 'STDOUT can be duplicated onto the stored temporary handle')
    or diag("open dup failed: $!");

seek($capture, 0, 0) or die "seek capture: $!";
my $output = <$capture>;
is($output, "captured\n", 'the duplicated STDOUT writes to the capture');

done_testing;
