use strict;
use warnings;

my $grammar = qr{
    (?(DEFINE)
        (?<document> (?&statement)+ )
        (?<statement> a | )
    )
    \A (?&document) \z
}x;

for my $source ('a', 'aa', 'aaa') {
    print $source =~ $grammar
        ? "ok - nullable recursive repeat matches $source\n"
        : "not ok - nullable recursive repeat rejects $source\n";
}
