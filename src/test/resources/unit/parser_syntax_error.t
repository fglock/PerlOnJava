use strict;
use warnings;
use Test::More;

eval q{sub parser_syntax_error_probe { if }};
like($@, qr/\Asyntax error at /, 'unexpected token reports Perl syntax error');

done_testing();
