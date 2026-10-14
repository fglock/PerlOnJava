use strict;
use warnings;
use Test::More;
use Issue1642::Utf8Importer -strict;

my $subject = "☃x";
ok(utf8::is_utf8($subject), 'indirect utf8 import decodes later source literals');
my $pattern = qr/^\x{2603}x$/u;
ok($subject =~ $pattern, 'Unicode pattern matches the imported-utf8 literal');

done_testing;
