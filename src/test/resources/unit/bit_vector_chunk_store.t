use strict;
use warnings;
use Test::More;
use Bit::Vector;

my $vector = Bit::Vector->new(64);

$vector->Chunk_Store(32, 32, 2147483648);
is($vector->Chunk_Read(32, 32), 2147483648,
    'stores a high-bit 32-bit chunk');

$vector->Chunk_Store(16, 0, 0xffff);
$vector->Chunk_Store(16, 8, 0xabcd);
is($vector->Chunk_Read(8, 0), 0xff,
    'preserves bits below an overlapping chunk');
is($vector->Chunk_Read(16, 8), 0xabcd,
    'replaces the requested chunk');
is($vector->Chunk_Read(8, 24), 0,
    'preserves bits above an overlapping chunk');

$vector->Chunk_Store(32, 56, 0x12345678);
is($vector->Chunk_Read(8, 56), 0x78,
    'clips a chunk that extends past the vector end');

my $wide_vector = Bit::Vector->new(64);
$wide_vector->Chunk_Store(64, 0, '18446744073709551615');
is($wide_vector->Chunk_Read(64, 0), -1,
    'stores and reads an all-ones 64-bit chunk as signed -1');

my $negative_vector = Bit::Vector->new(16);
$negative_vector->Chunk_Store(8, 0, -1);
is($negative_vector->Chunk_Read(8, 0), 255,
    'stores a negative chunk in two\'s-complement form');

done_testing();
