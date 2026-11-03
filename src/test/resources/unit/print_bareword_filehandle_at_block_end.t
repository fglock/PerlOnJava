use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

my ($seed_fh, $path) = tempfile();
close $seed_fh or die "close temporary file: $!";

my $input = "block output\n";
open F, '>', $path or die "open output filehandle: $!";
open my $input_fh, '<', \$input or die "open scalar input: $!";
while (<$input_fh>) { print F }
close $input_fh or die "close scalar input: $!";
close F or die "close output filehandle: $!";

open my $read_fh, '<', $path or die "read output file: $!";
my $output = do { local $/; <$read_fh> };
close $read_fh or die "close output reader: $!";
unlink $path or die "remove temporary file: $!";

is($output, "block output\n", 'bareword filehandle prints $_ before a block close');
done_testing;
