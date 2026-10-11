use strict;
use warnings;
use Test::More;

BEGIN {
    package Issue1642::Utf8Importer;
    sub import {
        require utf8;
        utf8->import;
    }
    $INC{'Issue1642/Utf8Importer.pm'} = __FILE__;
}

use Issue1642::Utf8Importer;

my $subject = "☃x";
ok(utf8::is_utf8($subject), 'indirect utf8 import decodes later source literals');
my $pattern = qr/^\x{2603}x$/u;
ok($subject =~ $pattern, 'Unicode pattern matches the imported-utf8 literal');

done_testing;
