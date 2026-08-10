use strict;
use warnings;

use Test::More;

my $invalid_rfc_token = qr/[\x00-\ ()<>@,;:"\/[\]?.=\\]/;

ok("\x00" =~ $invalid_rfc_token, 'unbraced hex escape starts a character-class range');
ok(' '    =~ $invalid_rfc_token, 'escaped space ends the character-class range');
ok('('    =~ $invalid_rfc_token, 'literal punctuation remains in the class');
ok('['    =~ $invalid_rfc_token, 'literal opening bracket remains in the class');
ok('A'    !~ $invalid_rfc_token, 'ordinary token character stays outside the class');
ok('!'    !~ $invalid_rfc_token, 'character above the range endpoint stays outside');

done_testing;
