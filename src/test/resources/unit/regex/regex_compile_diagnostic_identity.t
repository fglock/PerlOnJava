use strict;
use warnings;
use Test::More tests => 4;

my $single_quote_source = q{qr'abc} . chr(92) . q{N{def}'};
eval $single_quote_source;
like($@,
     qr/^Unknown charname 'def' in regex/,
     'apostrophe-delimited regex defers unknown charname to regex compilation');
unlike($@, qr/within pattern/,
       'apostrophe-delimited diagnostic does not claim interpolating source');

eval q{qr/(?<%)b/};
like($@, qr/^Group name must start with a non-digit word character in regex;/,
     'invalid named-capture first character keeps the corpus wording');
like($@, qr/m\/\(\?<% <-- HERE \)b\//,
     'unterminated named capture marks the invalid first character');
