use strict;
use warnings;
use Test::More;
use HTML::Entities qw(encode_entities encode_entities_numeric);

is(
    encode_entities('<>&', undef),
    '&lt;&gt;&amp;',
    'undef unsafe set uses the default named-entity set',
);
is(
    encode_entities_numeric('<>&', undef),
    '&#x3C;&#x3E;&#x26;',
    'undef unsafe set uses the default numeric-entity set',
);
is(
    encode_entities("\x8b"),
    '&#139;',
    'named encoder uses decimal numeric fallback for unnamed characters',
);

done_testing();
