use strict;
use warnings;
use utf8;
use Test::More;

for my $operation (qw(last next redo)) {
    eval "$operation Ｅ";
    like($@, qr/Label not found for "\Q$operation Ｅ\E" at/u,
        "$operation in eval reports its missing UTF-8 label");
}

done_testing;
