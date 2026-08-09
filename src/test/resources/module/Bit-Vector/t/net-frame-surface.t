use strict;
use warnings;
use Test::More tests => 11;
use Bit::Vector;

is(Bit::Vector::Version(), '7.4', 'reports the compatible API version');
cmp_ok(Bit::Vector::Word_Bits(), '>=', 32, 'word size is usable');

my $version = Bit::Vector->new_Dec(4, 4);
my $hlen = Bit::Vector->new_Dec(4, 5);
my $header = $version->Concat_List($hlen);

isa_ok($header, 'Bit::Vector');
is($header->Size, 8, 'concatenation adds vector widths');
is($header->to_Dec, 69, 'concatenation preserves network bit order');
is($header->Chunk_Read(4, 0), 5, 'zero offset reads low chunk');
is($header->Chunk_Read(4, 4), 4, 'higher offset reads high chunk');

is(Bit::Vector->new_Dec(4, 15)->to_Dec, -1,
    'decimal output uses fixed-width signed representation');
is(Bit::Vector->new_Dec(4, -1)->Chunk_Read(4, 0), 15,
    'negative input is stored in twos-complement form');

my @vectors = Bit::Vector->Create(3, 2);
is(scalar @vectors, 2, 'Create supports a vector count in list context');
is($vectors[0]->Size, 3, 'created vectors retain their width');
