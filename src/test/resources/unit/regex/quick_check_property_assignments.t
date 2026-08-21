use strict;
use warnings;
use Test::More;

for my $property (qw(NFD_QC NFD_Quick_Check NFKD_QC NFKD_Quick_Check)) {
    my $assigned = eval "qr/\\p{$property=No}/";
    is($@, '', "$property accepts an enumerated assignment");
    ok(defined $assigned, "$property assignment produces a regex");

    my $bare = eval "qr/\\p{$property}/";
    like($@, qr/Can't find Unicode property definition/, "$property bare form is rejected");
    ok(!defined $bare, "$property bare form does not produce a regex");
}

done_testing;
