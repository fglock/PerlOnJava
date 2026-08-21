use strict;
use warnings;
use Test::More tests => 2;

{
    use re Debug => 'ALL';
    my $regex = qr/phase36_named_debug_option/;
    ok('phase36_named_debug_option' =~ $regex,
       q{Debug => 'ALL' preserves ordinary matching});
}

{
    no re Debug => 'ALL';
    my $regex = qr/phase36_named_debug_disabled/;
    ok('phase36_named_debug_disabled' =~ $regex,
       q{no re Debug => 'ALL' preserves ordinary matching});
}
