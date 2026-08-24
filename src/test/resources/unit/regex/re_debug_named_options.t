use strict;
use warnings;
use Test::More tests => 2;

{
    use re Debug => 'ALL';
    my $regex = qr/regex_implementation_named_debug_option/;
    ok('regex_implementation_named_debug_option' =~ $regex,
       q{Debug => 'ALL' preserves ordinary matching});
}

{
    no re Debug => 'ALL';
    my $regex = qr/regex_implementation_named_debug_disabled/;
    ok('regex_implementation_named_debug_disabled' =~ $regex,
       q{no re Debug => 'ALL' preserves ordinary matching});
}
