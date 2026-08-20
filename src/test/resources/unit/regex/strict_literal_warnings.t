use strict;
use warnings;
use Test::More tests => 6;

for my $pattern (q!\b<GCB}!, q![ ]def]!, q!(?)!) {
    my @warnings;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        use re 'strict';
        eval "qr/$pattern/";
    }
    is($@, '', "$pattern compiles under strict regex mode");
    is(scalar @warnings, 1, "$pattern emits one strict regex warning");
}
