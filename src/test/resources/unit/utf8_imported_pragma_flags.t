use strict;
use warnings;
use utf8;
use lib 'src/test/resources/unit/lib';
use Test::More;
BEGIN {
    eval { require Import::Into; 1 }
        or plan skip_all => 'Import::Into is not installed';
}
use Encode qw(encode is_utf8);

{
    package Local::ImportedPragmaScope;
    use Local::ImportStrictWarnings;

    sub text { "⚒ ⚓ ⚔ ⚕ ⚖ ⚗ ⚘ ⚙" }
}

my $text = Local::ImportedPragmaScope::text();
my $bytes = encode('UTF-8', $text);

ok(is_utf8($text), 'strict and warnings imports preserve caller UTF-8 source flags');
is(unpack('H*', $bytes), 'e29a9220e29a9320e29a9420e29a9520e29a9620e29a9720e29a9820e29a99',
    'source characters retain their UTF-8 meaning after pragma import');
ok(!is_utf8($bytes), 'encoding the imported source produces an unflagged byte string');

done_testing;
