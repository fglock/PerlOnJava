use strict;
use warnings;
use Test::More;
use Storable qw(freeze thaw);

# IO::Async::Channel frames Storable data over pipes. Its binary stream can
# contain high-bit octets, which must remain single octets on an unlayered
# pipe; otherwise the frame length and payload diverge.
pipe(my $reader, my $writer) or die "pipe: $!";
binmode $reader;
binmode $writer;

my $frozen = freeze([10, 20]);
print {$writer} pack('I', length $frozen), $frozen or die "write: $!";
close $writer or die "close writer: $!";

read($reader, my $header, 4) == 4 or die "read header: $!";
my $length = unpack('I', $header);
read($reader, my $payload, $length) == $length or die "read payload: $!";

is_deeply(thaw($payload), [10, 20],
    'binary Storable payload round-trips through a pipe');

done_testing;
