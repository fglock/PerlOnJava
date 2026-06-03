use strict;
use warnings;
use Test::More;

eval q{sub parser_syntax_error_probe { if }};
like($@, qr/\Asyntax error at /, 'unexpected token reports Perl syntax error');

eval q{%@x=0};
like($@, qr/Can't modify hash dereference in repeat \(x\)/, 'hash dereference repeat assignment reports Perl error');

done_testing();
